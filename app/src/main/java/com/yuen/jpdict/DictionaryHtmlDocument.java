package com.yuen.jpdict;

import java.io.*;
import java.util.*;

/** A file-backed immutable entry page; cache, controller and WebView own references. */
final class DictionaryHtmlDocument implements Closeable {
    static final String POLICY = "default-src 'none'; style-src 'unsafe-inline' 'self'; "
            + "script-src 'unsafe-inline' 'unsafe-eval' 'self'; img-src 'self' data:; font-src 'self' data:; "
            + "media-src 'self'; connect-src 'self'; frame-src 'none'; object-src 'none'; "
            + "base-uri 'self'; form-action 'none'; worker-src 'none'; child-src 'none'";
    final File file;
    final int entries;
    private int owners = 1;
    DictionaryHtmlDocument(File file, int entries) { this.file = file; this.entries = entries; }
    synchronized DictionaryHtmlDocument retain() {
        if (owners == 0) throw new IllegalStateException("词条页面已释放");
        owners++; return this;
    }
    synchronized InputStream open() throws IOException {
        if (owners == 0) throw new IOException("词条页面已释放");
        return new FileInputStream(file);
    }
    @Override public synchronized void close() {
        if (owners > 0 && --owners == 0) file.delete();
    }
    static void header(Writer output, DictionaryRepository.Dictionary dictionary) throws IOException {
        output.write("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"");
        output.write(POLICY);
        output.write("\"><style>body{margin:12px;color:#171819;overflow-wrap:anywhere}img{max-width:100%;height:auto}audio{max-width:100%}</style>");
        for (DictionaryRepository.Document file : dictionary.files)
            if (file.path.toLowerCase(Locale.ROOT).endsWith(".css") && file.path.indexOf('/') < 0) {
                output.write("<link rel=\"stylesheet\" href=\""); output.write(android.net.Uri.encode(file.path)); output.write("\">");
            }
        output.write("</head><body>");
    }
}
