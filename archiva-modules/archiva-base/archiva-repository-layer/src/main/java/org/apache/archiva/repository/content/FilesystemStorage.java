package org.apache.archiva.repository.content;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.common.filelock.FileLockException;
import org.apache.archiva.common.filelock.FileLockManager;
import org.apache.archiva.common.filelock.FileLockTimeoutException;
import org.apache.archiva.common.filelock.Lock;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Implementation of <code>{@link RepositoryStorage}</code> where data is stored in the filesystem.
 *
 * All files are relative to a given base path. Path values are separated by '/', '..' is allowed to navigate
 * to a parent directory, but navigation out of the base path will lead to a exception.
 */
public class FilesystemStorage implements RepositoryStorage {

    private static final Logger log = LoggerFactory.getLogger(FilesystemStorage.class);

    private final Path basePath;
    private final FileLockManager fileLockManager;

    public FilesystemStorage(Path basePath, FileLockManager fileLockManager) throws IOException {
        this.basePath = basePath.normalize().toRealPath();
        this.fileLockManager = fileLockManager;
    }

    private Path normalize(final String path) {
        String nPath = path;
        while (nPath.startsWith("/")) {
            nPath = nPath.substring(1);
        }
        return Paths.get(nPath);
    }

    private Path getAssetPath(String path) throws IOException {
        Path assetPath = basePath.resolve(normalize(path)).normalize();
        if (!assetPath.startsWith(basePath))
        {
            throw new IOException("Path navigation out of allowed scope: "+path);
        }
        return assetPath;
    }

    @Override
    public void consumeData( StorageAsset asset, Consumer<InputStream> consumerFunction, boolean readLock ) throws IOException
    {
        final Path path = asset.getFilePath();
        try {
            if (readLock) {
                consumeDataLocked( path, consumerFunction );
            } else
            {
                try ( InputStream is = Files.newInputStream( path ) )
                {
                    consumerFunction.accept( is );
                }
                catch ( IOException e )
                {
                    log.error("Could not read the input stream from file {}", path);
                    throw e;
                }
            }
        } catch (RuntimeException e)
        {
            log.error( "Runtime exception during data consume from artifact {}. Error: {}", path, e.getMessage() );
            throw new IOException( e );
        }

    }

    private void consumeDataLocked( Path file, Consumer<InputStream> consumerFunction) throws IOException
    {

        final Lock lock;
        try
        {
            lock = fileLockManager.readFileLock( file );
            try ( InputStream is = Files.newInputStream( lock.getFile()))
            {
                consumerFunction.accept( is );
            }
            catch ( IOException e )
            {
                log.error("Could not read the input stream from file {}", file);
                throw e;
            } finally
            {
                fileLockManager.release( lock );
            }
        }
        catch ( FileLockException | FileNotFoundException | FileLockTimeoutException e)
        {
            log.error("Locking error on file {}", file);
            throw new IOException(e);
        }
    }


    @Override
    public StorageAsset getAsset( String path )
    {
        try {
            return new FilesystemAsset( path, getAssetPath(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Path navigates outside of base directory "+path);
        }
    }

    @Override
    public StorageAsset addAsset( String path, boolean container )
    {
        try {
            return new FilesystemAsset( path, getAssetPath(path), container);
        } catch (IOException e) {
            throw new IllegalArgumentException("Path navigates outside of base directory "+path);
        }
    }

    @Override
    public void removeAsset( StorageAsset asset ) throws IOException
    {
        Files.delete(asset.getFilePath());
    }

    @Override
    public StorageAsset moveAsset( StorageAsset origin, String destination ) throws IOException
    {
        boolean container = origin.isContainer();
        FilesystemAsset newAsset = new FilesystemAsset( destination, getAssetPath(destination), container );
        Files.move(origin.getFilePath(), newAsset.getFilePath());
        return newAsset;
    }

    @Override
    public StorageAsset copyAsset( StorageAsset origin, String destination ) throws IOException
    {
        boolean container = origin.isContainer();
        FilesystemAsset newAsset = new FilesystemAsset( destination, getAssetPath(destination), container );
        if (Files.exists(newAsset.getFilePath())) {
            throw new IOException("Destination file exists already "+ newAsset.getFilePath());
        }
        if (Files.isDirectory( origin.getFilePath() ))
        {
            FileUtils.copyDirectory(origin.getFilePath( ).toFile(), newAsset.getFilePath( ).toFile() );
        } else if (Files.isRegularFile( origin.getFilePath() )) {
            FileUtils.copyFile(origin.getFilePath( ).toFile(), newAsset.getFilePath( ).toFile() );
        }
        return newAsset;
    }

}