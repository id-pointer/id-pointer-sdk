package cn.teleinfo.idpointer.sdk.core.stream;

import java.io.*;

public class AtomicFile {
    private final File mBaseName;
    private final File mBackupName;
    private final boolean sync;

    /**
     * Create a new AtomicFile for a file located at the given File path.
     * The secondary backup file will be the same file path with ".safe" appended.
     * Files are fsync'd on write.
     */
    public AtomicFile(File baseName) {
        this(baseName, true);
    }
    
    /**
     * Create a new AtomicFile for a file located at the given File path.
     * The secondary backup file will be the same file path with ".safe" appended.
     * If sync is true, files are fsync'd on write.
     */
    public AtomicFile(File baseName, boolean sync) {
        mBaseName = baseName;
        mBackupName = new File(baseName.getPath() + ".safe");
        this.sync = sync;
    }

    /**
     * Return the path to the base file.  You should not generally use this,
     * as the data at that path may not be valid.
     */
    public File getBaseFile() {
        return mBaseName;
    }

    /**
     * Returns if the file (or the backup) exists
     */
    public boolean exists() {
        return mBaseName.exists() || mBackupName.exists();
    }
    
    /**
     * Delete the atomic file.  This deletes both the base and backup files.
     */
    public void delete() {
        mBaseName.delete();
        mBackupName.delete();
    }

    /**
     * Start a new write operation on the file.  This returns a FileOutputStream
     * to which you can write the new file data.  The existing file is replaced
     * with the new data.  You <em>must not</em> directly close the given
     * FileOutputStream; instead call either {@link #finishWrite(FileOutputStream)}
     * or {@link #failWrite(FileOutputStream)}.
     *
     * <p>Note that if another thread is currently performing
     * a write, this will simply replace whatever that thread is writing
     * with the new file being written by this thread, and when the other
     * thread finishes the write the new write operation will no longer be
     * safe (or will be lost).  You must do your own threading protection for
     * access to AtomicFile.
     */
    public FileOutputStream startWrite() throws IOException {
        // Rename the current file so it may be used as a backup during the next read
        if (mBaseName.exists()) {
            if (!mBackupName.exists()) {
                if (!mBaseName.renameTo(mBackupName)) {
                    throw new IOException("Couldn't rename file " + mBaseName
                            + " to backup file " + mBackupName);
                }
            } else {
                mBaseName.delete();
            }
        }
        FileOutputStream str = null;
        try {
            str = new FileOutputStream(mBaseName);
        } catch (FileNotFoundException e) {
            File parent = mBaseName.getParentFile();
            if (!parent.mkdir()) {
                throw new IOException("Couldn't create directory for " + mBaseName);
            }
            try {
                str = new FileOutputStream(mBaseName);
            } catch (FileNotFoundException e2) {
                throw new IOException("Couldn't create " + mBaseName);
            }
        }
        return str;
    }

    /**
     * Call when you have successfully finished writing to the stream
     * returned by {@link #startWrite()}.  This will close, sync, and
     * commit the new data.  The next attempt to read the atomic file
     * will return the new file stream.
     */
    public void finishWrite(FileOutputStream str) throws IOException {
        if (str != null) {
            sync(str);
            str.close();
            mBackupName.delete();
        }
    }

    /**
     * Call when you have failed for some reason at writing to the stream
     * returned by {@link #startWrite()}.  This will close the current
     * write stream, and roll back to the previous state of the file.
     */
    public void failWrite(FileOutputStream str) throws IOException {
        if (str != null) {
            sync(str);
            str.close();
            mBaseName.delete();
            mBackupName.renameTo(mBaseName);
        }
    }

    /**
     * Open the atomic file for reading.  If there previously was an
     * incomplete write, this will roll back to the last good data before
     * opening for read.  You should call close() on the FileInputStream when
     * you are done reading from it.
     *
     * <p>Note that if another thread is currently performing
     * a write, this will incorrectly consider it to be in the state of a bad
     * write and roll back, causing the new data currently being written to
     * be dropped.  You must do your own threading protection for access to
     * AtomicFile.
     */
    public FileInputStream openRead() throws IOException {
        if (mBackupName.exists()) {
            mBaseName.delete();
            if(!mBackupName.renameTo(mBaseName)) throw new IOException("Couldn't rename file " + mBackupName
                    + " to backup file " + mBaseName);
        }
        return new FileInputStream(mBaseName);
    }

    /**
     * A convenience for {@link #openRead()} that also reads all of the
     * file contents into a byte array which is returned.
     */
    public byte[] readFully() throws IOException {
        FileInputStream stream = openRead();
        try {
            int pos = 0;
            int avail = stream.available();
            byte[] data = new byte[avail];
            while (true) {
                int amt = stream.read(data, pos, data.length-pos);
                //Log.i("foo", "Read " + amt + " bytes at " + pos
                //        + " of avail " + data.length);
                if (amt <= 0) {
                    //Log.i("foo", "**** FINISHED READING: pos=" + pos
                    //        + " len=" + data.length);
                    return data;
                }
                pos += amt;
                avail = stream.available();
                if (avail > data.length-pos) {
                    byte[] newData = new byte[pos+avail];
                    System.arraycopy(data, 0, newData, 0, pos);
                    data = newData;
                }
            }
        } finally {
            stream.close();
        }
    }

    /**
     * A convenience for writing an entire byte array exactly
     * @param bytes the bytes to write
     */
    @SuppressWarnings("resource")
    public void writeFully(byte[] bytes) throws IOException {
        FileOutputStream str = startWrite();
        try {
            str.write(bytes);
            finishWrite(str);
        } catch(IOException e) {
            failWrite(str);
            throw e;
        }
    }
    
    private boolean sync(FileOutputStream stream) {
        if (!sync) return true;
        try {
            if (stream != null) {
                stream.getFD().sync();
            }
            return true;
        } catch (IOException e) {
        }
        return false;
    }
}
