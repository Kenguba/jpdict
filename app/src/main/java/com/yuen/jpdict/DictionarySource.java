package com.yuen.jpdict;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** SAF Adapter for the same positional source contract as app-private files. */
final class DictionarySource implements MdictReader.Source {
    private final ParcelFileDescriptor.AutoCloseInputStream stream;
    private final FileChannel channel;
    private final long size;

    DictionarySource(ContentResolver resolver, Uri uri) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("文件不可访问；请重新关联词典");
        stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        channel = stream.getChannel();
        long length;
        try {
            length = channel.size();
            if (length <= 0) throw new IOException("文件为空或提供方未提供长度");
            // Probe both ends; pipes and streaming providers must be rejected.
            byte[] probe = new byte[1];
            read(0, probe, 0, 1);
            read(length - 1, probe, 0, 1);
        } catch (IOException | RuntimeException error) {
            stream.close();
            throw new MdictReader.Failure(MdictReader.Error.INACCESSIBLE,
                    "提供方不支持可靠随机读取；请选择复制到 JPDict，或重新关联文件");
        }
        size = length;
    }
    public long size() { return size; }
    public void read(long position, byte[] target, int offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(target, offset, length);
        while (buffer.hasRemaining()) {
            MdictReader.checkCancelled();
            int count = channel.read(buffer, position);
            if (count <= 0) throw new IOException("外部文件读取失败或已被删除；请重新关联");
            position += count;
        }
    }
    public void close() throws IOException { stream.close(); }
}
