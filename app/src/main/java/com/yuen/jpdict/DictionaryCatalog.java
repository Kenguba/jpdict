package com.yuen.jpdict;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Catalog schema stays independent of installation records; no network client. */
final class DictionaryCatalog {
    enum Mode { PREVIEW, AVAILABLE, DISABLED }
    static final class Package {
        final String id, version, name, description, publisher;
        final Mode mode;
        final JSONObject languages, license;
        final JSONArray tags, files, dictionaries;
        final String iconUrl;
        final Long sizeBytes, minAppVersionCode;
        Package(JSONObject value) throws JSONException, IOException {
            id = value.getString("packageId"); version = value.getString("version");
            name = value.getString("name"); description = value.getString("description"); publisher = value.getString("publisher");
            mode = Mode.valueOf(value.getJSONObject("distribution").getString("mode"));
            files = value.getJSONObject("distribution").getJSONArray("files"); dictionaries = value.getJSONArray("dictionaries");
            languages = value.getJSONObject("languages"); tags = value.getJSONArray("tags"); license = value.getJSONObject("license");
            iconUrl = value.isNull("iconUrl") ? null : value.getString("iconUrl");
            sizeBytes = value.isNull("sizeBytes") ? null : value.getLong("sizeBytes");
            minAppVersionCode = value.isNull("minAppVersionCode") ? null : value.getLong("minAppVersionCode");
            Set<String> paths = new HashSet<>(), ids = new HashSet<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                if (!paths.add(DictionaryPaths.safe(file.getString("path")))) throw new IOException("Catalog 文件路径重复");
                String role = file.getString("role");
                if (!Arrays.asList("MDX", "MDD", "RESOURCE").contains(role)) throw new IOException("未知 Catalog 文件角色");
                file.getString("dictId");
                for (String field : new String[]{"partIndex", "sizeBytes", "sha256", "url"}) if (!file.has(field)) throw new IOException("Catalog 缺少 " + field);
            }
            for (int i = 0; i < dictionaries.length(); i++) {
                JSONObject dictionary = dictionaries.getJSONObject(i);
                if (!ids.add(dictionary.getString("dictId"))) throw new IOException("Catalog 词典 ID 重复");
                dictionary.getString("name"); DictionaryPaths.safe(dictionary.getString("mdxPath"));
                for (String field : new String[]{"mddPaths", "resourcePaths"}) {
                    JSONArray list = dictionary.getJSONArray(field);
                    for (int n = 0; n < list.length(); n++) DictionaryPaths.safe(list.getString(n));
                }
            }
        }
    }
    static List<Package> load(Context context) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open("dictionary_catalog.json")) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() > 1024 * 1024 - count) throw new IOException("Catalog 超过 1 MB");
                bytes.write(buffer, 0, count);
            }
        }
        JSONObject catalog = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        if (catalog.getInt("schemaVersion") != 1) throw new IOException("不支持的 Catalog 版本");
        catalog.getString("catalogId");
        JSONArray packages = catalog.getJSONArray("packages"); List<Package> list = new ArrayList<>();
        for (int i = 0; i < packages.length(); i++) list.add(new Package(packages.getJSONObject(i)));
        return list;
    }
}
