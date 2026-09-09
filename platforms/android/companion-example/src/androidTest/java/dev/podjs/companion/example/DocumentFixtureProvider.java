package dev.podjs.companion.example;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Synthetic test APK content only. Paths are enumerated; there is no user-data access. */
public class DocumentFixtureProvider extends ContentProvider {
    static final String AUTHORITY="dev.podjs.companion.example.test.documents";
    private static volatile boolean waiting;
    public static byte[] bytes() { byte[] result=new byte[131079]; for(int n=0;n<result.length;n++) result[n]=(byte)(n*17+3); return result; }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public android.content.res.AssetFileDescriptor openTypedAssetFile(Uri uri,String filter,Bundle options,CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri,"r",signal);
    }
    @Override public android.content.res.AssetFileDescriptor openAssetFile(Uri uri,String mode,CancellationSignal signal) throws FileNotFoundException {
        // ContentProvider's default typed/asset implementations discard the signal.
        return new android.content.res.AssetFileDescriptor(openFile(uri,mode,signal),0,android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH);
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { return openFile(uri,mode,null); }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Read only fixture");
        try {
            String path=uri.getPath();
            if("/wait".equals(path)) {
                waiting=true;
                try { for(int n=0;n<500;n++) { if(signal!=null) signal.throwIfCanceled(); Thread.sleep(20); } }
                finally { waiting=false; }
                throw new FileNotFoundException("Fixture timeout");
            }
            if("/pipe".equals(path)) { ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe(); pipe[1].close(); return pipe[0]; }
            if(!"/valid".equals(path) && !"/oversize".equals(path)) throw new FileNotFoundException("Unknown fixture");
            File file=new File(getContext().getCacheDir(),path.equals("/valid")?"valid-fixture.bin":"oversize-fixture.bin");
            if(path.equals("/valid")) java.nio.file.Files.write(file.toPath(),bytes());
            else try(java.io.RandomAccessFile large=new java.io.RandomAccessFile(file,"rw")) { large.setLength(16L*1024*1024+1); }
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        } catch(FileNotFoundException error) { throw error; }
        catch(android.os.OperationCanceledException error) { throw error; }
        catch(Exception error) { throw new FileNotFoundException("Fixture unavailable: "+error.getClass().getSimpleName()); }
    }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        Bundle result=new Bundle(); if(method.equals("waiting")) { result.putBoolean("waiting",waiting); return result; }
        if(!method.equals("seedDownload") && !method.equals("removeDownload")) return result;
        if(argument==null || !java.util.UUID.fromString(argument).toString().equals(argument)) throw new IllegalArgumentException("Invalid fixture token");
        long identity=android.os.Binder.clearCallingIdentity();
        try {
            android.content.SharedPreferences records=getContext().getSharedPreferences("download-fixtures",0);
            String key="download-"+argument, saved=records.getString(key,null);
            android.content.ContentResolver resolver=getContext().getContentResolver();
            if(method.equals("removeDownload")) {
                if(saved!=null) { resolver.delete(Uri.parse(saved),null,null); records.edit().remove(key).commit(); }
                return result;
            }
            if(saved!=null || records.getAll().size()>=4) throw new IllegalStateException("Fixture already exists or quota reached");
            String name="podjs-picker-"+argument+".bin";
            ContentValues values=new ContentValues(); values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS+"/PodJSFixture/");
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
            Uri inserted=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(inserted==null) throw new IllegalStateException("Cannot seed Downloads");
            boolean kept=false;
            try {
                try(java.io.OutputStream output=resolver.openOutputStream(inserted)) { if(output==null) throw new IllegalStateException("No fixture output"); output.write(bytes()); }
                values.clear(); values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,0); resolver.update(inserted,values,null,null);
                try(Cursor row=resolver.query(inserted,new String[]{android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME},null,null,null)) {
                    if(row==null || !row.moveToFirst()) throw new IllegalStateException("Fixture owner missing"); result.putString("owner",row.getString(0));
                }
                if(!records.edit().putString(key,inserted.toString()).commit()) throw new IllegalStateException("Cannot record fixture ownership");
                result.putString("uri",inserted.toString()); result.putString("name",name); kept=true; return result;
            } finally { if(!kept) resolver.delete(inserted,null,null); }
        } catch(Exception error) { throw new IllegalStateException("Download fixture failed",error); }
        finally { android.os.Binder.restoreCallingIdentity(identity); }
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
