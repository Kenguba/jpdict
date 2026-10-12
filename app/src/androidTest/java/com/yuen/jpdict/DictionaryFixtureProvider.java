package com.yuen.jpdict;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;

/** Read-only, synthetic test data. Never shipped in the production APK. */
public final class DictionaryFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private byte[] data() { byte[] data = new byte[256]; for(int i=0;i<data.length;i++) data[i]=(byte)i; return data; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        try {
            if (uri.getPath().equals("/pipe")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(()->{try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write(data());}catch(IOException ignored){}},"dict-test-pipe").start();
                return pipe[0];
            }
            if (!uri.getPath().equals("/seek")) throw new FileNotFoundException("missing");
            File file=new File(getContext().getCacheDir(),"seek-test.bin");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(data());}
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        } catch(IOException error){throw new FileNotFoundException(error.getMessage());}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        MatrixCursor cursor=new MatrixCursor(projection);
        if (uri.getPath().endsWith("/children")) {
            String parent = DocumentsContract.getDocumentId(uri);
            String[][] files = parent.equals("fixture")
                    ? new String[][]{{"fixture/book", "book.mdx", "application/octet-stream"},
                            {"fixture/assets", "assets", DocumentsContract.Document.MIME_TYPE_DIR},
                            {"fixture/git", ".git", DocumentsContract.Document.MIME_TYPE_DIR}}
                    : new String[][]{{"fixture/css", "local.css", "text/css"}};
            for (String[] file : files) {
                Object[] row = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) {
                    switch (projection[i]) {
                        case "document_id": row[i] = file[0]; break;
                        case "_display_name": row[i] = file[1]; break;
                        case "mime_type": row[i] = file[2]; break;
                        default: row[i] = 256L; break;
                    }
                }
                cursor.addRow(row);
            }
            return cursor;
        }
        Object[] row=new Object[projection.length];
        for(int i=0;i<projection.length;i++) row[i]=projection[i].equals("_display_name")?"seek-test.bin":projection[i].equals("last_modified")?1L:256L;
        cursor.addRow(row);return cursor;
    }
    @Override public String getType(Uri uri){return "application/octet-stream";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
}
