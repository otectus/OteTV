package com.otectus.otetv.worker.media;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * A local file granted by the player. The client resolved symlinks before granting; the worker re-resolves and refuses
 * the file if the real path changed, if it is not a regular file, or if the path is not absolute (section 11.1).
 */
final class FileByteSource implements ByteSource {
    private final RandomAccessFile file;
    private final long size;

    FileByteSource(String location) throws IOException {
        Path path = Path.of(location);
        if (!path.isAbsolute()) {
            throw new IOException("granted path must be absolute");
        }
        Path real = path.toRealPath();
        if (!real.equals(path.normalize()) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("granted file changed or is not a regular file");
        }
        this.file = new RandomAccessFile(real.toFile(), "r");
        this.size = file.length();
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean seekable() {
        return true;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        return file.read(buf, off, len);
    }

    @Override
    public void seek(long position) throws IOException {
        file.seek(Math.max(0, Math.min(position, size)));
    }

    @Override
    public long position() {
        try {
            return file.getFilePointer();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
