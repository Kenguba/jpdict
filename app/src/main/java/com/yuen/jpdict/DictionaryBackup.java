package com.yuen.jpdict;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Streaming ZIP transfer. Files are verified before one database transaction;
 * immutable installation generations make interrupted restores recoverable. */
final class DictionaryBackup {
    private static final int MAX_MANIFEST = 1024 * 1024, MAX_FILES = 10000;
    private DictionaryBackup() {}

    static void write(DictionaryRepository repo, Uri uri, boolean full, DictionaryRepository.Progress progress) throws Exception {
        repo.reload();
        JSONObject manifest = new JSONObject().put("schemaVersion", 1).put("full", full)
                .put("autoplay", LookupSpeechSettings.autoplay(repo.context()));
        JSONArray dictionaries = new JSONArray();
        OutputStream stream = repo.context().getContentResolver().openOutputStream(uri, "wt");
        if (stream == null) throw new IOException("备份位置不可写");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(stream))) {
            byte[] buffer = new byte[65536];
            for (DictionaryRepository.Dictionary d : repo.list()) {
                JSONObject record = new JSONObject().put("dictId", d.id).put("name", d.name)
                        .put("packageId", d.packageId).put("installedVersion", d.installedVersion)
                        .put("sourceType", d.source.name()).put("storageMode", d.storage.name())
                        .put("enabled", d.enabled).put("sortOrder", d.order).put("fileFingerprint", d.fingerprint)
                        .put("hasFiles", full && d.storage == DictionaryRepository.StorageMode.APP_PRIVATE && "INSTALLED".equals(d.status))
                        .put("installedAt", d.installedAt).put("updatedAt", d.updatedAt);
                JSONArray files = new JSONArray();
                for (DictionaryRepository.Document f : d.files) {
                    MdictReader.checkCancelled();
                    JSONObject file = new JSONObject().put("path", DictionaryPaths.safe(f.path)).put("size", f.size);
                    if (full && d.storage == DictionaryRepository.StorageMode.APP_PRIVATE && "INSTALLED".equals(d.status)) {
                        String archive = "dictionaries/" + d.id + "/" + f.path;
                        file.put("archive", archive);
                        File source = new File(f.uri.getPath());
                        if (!source.getCanonicalPath().startsWith(repo.directory().getCanonicalPath() + File.separator)) throw new IOException("内部词典文件路径越界");
                        zip.putNextEntry(new ZipEntry(archive));
                        MessageDigest hash = MessageDigest.getInstance("SHA-256"); long copied = 0;
                        try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
                            int count;
                            while ((count = in.read(buffer)) != -1) {
                                MdictReader.checkCancelled(); zip.write(buffer, 0, count); hash.update(buffer, 0, count); copied += count;
                                progress.update("备份 " + d.name + " / " + f.path, copied, source.length());
                            }
                        }
                        if (copied != source.length()) throw new IOException("备份期间文件发生变化");
                        zip.closeEntry(); file.put("size", copied).put("sha256", hex(hash.digest()));
                    }
                    // SAF grants and secrets are device-bound and never exported.
                    files.put(file);
                }
                record.put("files", files); dictionaries.put(record);
            }
            manifest.put("dictionaries", dictionaries);
            byte[] data = manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (data.length > MAX_MANIFEST) throw new IOException("备份清单超过 1 MB");
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(data); zip.closeEntry();
        } catch (Exception failure) {
            // A cancelled/truncated backup must never be presented as successful.
            try { repo.context().getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) {}
            throw failure;
        }
    }

    static void restore(DictionaryRepository repo, Uri uri, DictionaryRepository.Progress progress) throws Exception {
        File root = repo.directory(); if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法建立词典目录");
        File stage = new File(root, ".restore-" + UUID.randomUUID());
        if (!stage.mkdir()) throw new IOException("无法建立恢复临时目录");
        List<File> moved = new ArrayList<>(); boolean committed = false;
        try {
            Map<String, String> hashes = new HashMap<>(); Map<String, Long> sizes = new HashMap<>();
            Set<String> seen = new HashSet<>();
            InputStream stream = repo.context().getContentResolver().openInputStream(uri);
            if (stream == null) throw new IOException("备份文件不可读");
            byte[] buffer = new byte[65536]; long total = 0;
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(stream))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    MdictReader.checkCancelled(); String path = archivePath(entry.getName());
                    if (entry.isDirectory()) continue;
                    if (seen.size() >= MAX_FILES || !seen.add(path)) throw new IOException("备份文件过多或重复路径");
                    if (!path.equals("manifest.json") && !path.matches("dictionaries/[0-9a-f-]{36}/.+")) throw new IOException("未知备份文件路径");
                    File target = DictionaryRepository.inside(stage, path);
                    if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("无法创建恢复目录");
                    MessageDigest hash = MessageDigest.getInstance("SHA-256"); long copied = 0;
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            MdictReader.checkCancelled();
                            if (root.getUsableSpace() < count + 16L * 1024 * 1024) throw new IOException("恢复空间不足");
                            copied += count; total += count;
                            if (path.equals("manifest.json") && copied > MAX_MANIFEST) throw new IOException("备份清单超过 1 MB");
                            out.write(buffer, 0, count); hash.update(buffer, 0, count);
                            progress.update("解包 " + path, total, -1);
                        }
                        out.getFD().sync();
                    }
                    hashes.put(path, hex(hash.digest())); sizes.put(path, copied); zip.closeEntry();
                }
            }
            File manifestFile = new File(stage, "manifest.json");
            if (!manifestFile.isFile()) throw new IOException("备份缺少清单");
            StringBuilder json = new StringBuilder();
            try (Reader reader = new InputStreamReader(new FileInputStream(manifestFile), java.nio.charset.StandardCharsets.UTF_8)) {
                char[] chars = new char[8192]; int count; while ((count = reader.read(chars)) != -1) json.append(chars, 0, count);
            }
            JSONObject manifest = new JSONObject(json.toString());
            if (manifest.getInt("schemaVersion") != 1) throw new IOException("不支持的备份版本");
            JSONArray dictionaries = manifest.getJSONArray("dictionaries");
            if (dictionaries.length() > 512) throw new IOException("备份词典数量超过 512");
            Set<String> ids = new HashSet<>(), expected = new HashSet<>(); expected.add("manifest.json");
            List<ContentValues> records = new ArrayList<>();
            for (int i = 0; i < dictionaries.length(); i++) {
                JSONObject d = dictionaries.getJSONObject(i); String id = d.getString("dictId");
                if (!id.matches("[0-9a-f-]{36}") || !UUID.fromString(id).toString().equals(id) || !ids.add(id)) throw new IOException("无效或重复词典 ID");
                DictionaryRepository.StorageMode storage = DictionaryRepository.StorageMode.valueOf(d.getString("storageMode"));
                DictionaryRepository.SourceType source = DictionaryRepository.SourceType.valueOf(d.getString("sourceType"));
                JSONArray files = d.getJSONArray("files"); if (files.length() == 0 || files.length() > MAX_FILES) throw new IOException("词典清单文件数量无效");
                List<DictionaryRepository.Document> documents = new ArrayList<>();
                Set<String> paths = new HashSet<>(); boolean hasFiles = manifest.getBoolean("full") && d.getBoolean("hasFiles") && storage == DictionaryRepository.StorageMode.APP_PRIVATE;
                File generation = new File(new File(root, id), UUID.randomUUID().toString());
                int mainCount = 0;
                for (int n = 0; n < files.length(); n++) {
                    JSONObject f = files.getJSONObject(n); String path = DictionaryPaths.safe(f.getString("path"));
                    if (!paths.add(path.toLowerCase(Locale.ROOT))) throw new IOException("词典资源路径重复");
                    if (path.toLowerCase(Locale.ROOT).endsWith(".mdx")) mainCount++;
                    long length = f.getLong("size"); if (length < -1) throw new IOException("无效文件大小");
                    if (hasFiles) {
                        String archive = "dictionaries/" + id + "/" + path;
                        if (!archive.equals(f.getString("archive")) || !expected.add(archive)) throw new IOException("备份资源归属不符");
                        if (!Objects.equals(hashes.get(archive), f.getString("sha256")) || !Objects.equals(sizes.get(archive), length)) throw new IOException("备份文件完整性校验失败：" + path);
                        File staged = DictionaryRepository.inside(stage, archive);
                        if (path.toLowerCase(Locale.ROOT).matches(".*\\.(mdx|mdd)$")) {
                            try (MdictReader parsed = MdictReader.open(staged)) {
                                if (parsed.getMetadata().resource != path.toLowerCase(Locale.ROOT).endsWith(".mdd")) throw new IOException("备份 MDX/MDD 类型不符");
                                parsed.validate((done, totalBlocks) -> progress.update("验证 " + path, done, totalBlocks));
                            }
                        }
                        documents.add(new DictionaryRepository.Document(path, Uri.fromFile(DictionaryRepository.inside(generation, path)), length));
                    } else documents.add(new DictionaryRepository.Document(path, Uri.EMPTY, length));
                }
                if (mainCount != 1) throw new IOException("每本词典必须恰有一个 MDX");
                boolean existingFiles = false;
                if (!hasFiles) {
                    for (DictionaryRepository.Dictionary existing : repo.list()) {
                        if (existing.id.equals(id) && existing.storage == storage
                                && existing.fingerprint.equals(d.getString("fileFingerprint")) && "INSTALLED".equals(existing.status)) {
                            documents = new ArrayList<>(existing.files); existingFiles = true; break;
                        }
                    }
                }
                if (hasFiles) {
                    File dictionaryRoot = generation.getParentFile();
                    if (!dictionaryRoot.isDirectory() && !dictionaryRoot.mkdirs()) throw new IOException("无法建立词典恢复目录");
                    File staged = new File(stage, "dictionaries/" + id);
                    if (!staged.renameTo(generation)) throw new IOException("无法提交词典文件");
                    moved.add(generation);
                }
                ContentValues v = new ContentValues();
                v.put("dict_id", id); v.put("name", d.getString("name")); v.put("package_id", d.getString("packageId"));
                v.put("installed_version", d.getString("installedVersion")); v.put("source_type", source.name()); v.put("storage_mode", storage.name());
                v.put("enabled", d.getBoolean("enabled") ? 1 : 0); v.put("sort_order", d.getInt("sortOrder"));
                v.put("index_state", hasFiles || existingFiles ? "按需索引" : "待重新关联"); v.put("install_state", hasFiles || existingFiles ? "INSTALLED" : "NEEDS_RELINK");
                v.put("fingerprint", d.getString("fileFingerprint")); v.put("installed_at", d.getLong("installedAt"));
                v.put("updated_at", System.currentTimeMillis()); v.put("files", DictionaryRepository.filesJson(documents).toString()); records.add(v);
            }
            if (!seen.equals(expected)) throw new IOException("备份含有未登记文件");
            MdictReader.checkCancelled();
            SQLiteDatabase db = repo.database().getWritableDatabase(); db.beginTransaction();
            try {
                for (ContentValues row : records) if (db.insertWithOnConflict("dictionaries", null, row, SQLiteDatabase.CONFLICT_REPLACE) == -1) throw new IOException("恢复词典记录失败");
                try (android.database.Cursor budget=db.rawQuery("SELECT count(*), coalesce(sum(length(files)),0) FROM dictionaries",null)) {
                    if(budget.moveToFirst()&&(budget.getInt(0)>512||budget.getLong(1)>4L*1024*1024))throw new IOException("恢复清单超过内存限制");
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            committed = true;
            LookupSpeechSettings.setAutoplay(repo.context(), manifest.optBoolean("autoplay", true));
        } finally {
            if (!committed) for (File generation : moved) DictionaryRepository.removeTree(generation);
            DictionaryRepository.removeTree(stage);
        }
    }
    static String archivePath(String raw) throws IOException {
        if (raw.startsWith("/") || raw.contains("\\") || raw.indexOf('\u0000') >= 0) throw new IOException("不安全备份路径");
        String safe = DictionaryPaths.safe(raw);
        if (!safe.equals(raw) && !raw.equals(safe + "/")) throw new IOException("不安全备份路径");
        return safe;
    }
    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(); for (byte b : data) result.append(String.format(Locale.ROOT, "%02x", b & 255)); return result.toString();
    }
}
