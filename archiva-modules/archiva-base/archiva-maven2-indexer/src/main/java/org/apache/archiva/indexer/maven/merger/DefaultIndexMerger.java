package org.apache.archiva.indexer.maven.merger;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.common.utils.FileUtils;
import org.apache.archiva.indexer.UnsupportedBaseContextException;
import org.apache.archiva.indexer.merger.IndexMerger;
import org.apache.archiva.indexer.merger.IndexMergerException;
import org.apache.archiva.indexer.merger.IndexMergerRequest;
import org.apache.archiva.indexer.merger.TemporaryGroupIndex;
import org.apache.archiva.repository.RepositoryRegistry;
import org.apache.archiva.repository.RepositoryType;
import org.apache.commons.lang.time.StopWatch;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.context.ContextMemberProvider;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.context.StaticContextMemberProvider;
import org.apache.maven.index.packer.IndexPacker;
import org.apache.maven.index.packer.IndexPackingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * @author Olivier Lamy
 * @since 1.4-M2
 */
@Service("indexMerger#default")
public class DefaultIndexMerger
    implements IndexMerger
{

    @Inject
    RepositoryRegistry repositoryRegistry;

    private Logger log = LoggerFactory.getLogger( getClass() );


    private final IndexPacker indexPacker;

    private Indexer indexer;

    private final List<IndexCreator> indexCreators;

    private List<TemporaryGroupIndex> temporaryGroupIndexes = new CopyOnWriteArrayList<>();

    private List<IndexingContext>  temporaryContextes = new CopyOnWriteArrayList<>(  );

    private List<String> runningGroups = new CopyOnWriteArrayList<>();

    @Inject
    public DefaultIndexMerger( Indexer indexer, IndexPacker indexPacker, List<IndexCreator> indexCreators )
    {
        this.indexer = indexer;
        this.indexPacker = indexPacker;
        this.indexCreators = indexCreators;
    }

    @Override
    public IndexingContext buildMergedIndex( IndexMergerRequest indexMergerRequest )
        throws IndexMergerException
    {
        String groupId = indexMergerRequest.getGroupId();

        if ( runningGroups.contains( groupId ) )
        {
            log.info( "skip build merge remote indexes for id: '{}' as already running", groupId );
            return null;
        }

        runningGroups.add( groupId );

        StopWatch stopWatch = new StopWatch();
        stopWatch.reset();
        stopWatch.start();

        Path mergedIndexDirectory = indexMergerRequest.getMergedIndexDirectory();

        String tempRepoId = mergedIndexDirectory.getFileName().toString();

        try
        {
            Path indexLocation = mergedIndexDirectory.resolve( indexMergerRequest.getMergedIndexPath() );

            List<IndexingContext> members = indexMergerRequest.getRepositoriesIds( ).stream( ).map( id ->
                repositoryRegistry.getRepository( id ) ).filter( repo -> repo.getType().equals( RepositoryType.MAVEN ) )
                .map( repo -> {
                    try
                    {
                        return repo.getIndexingContext().getBaseContext( IndexingContext.class );
                    }
                    catch ( UnsupportedBaseContextException e )
                    {
                        return null;
                        // Ignore
                    }
                } ).filter( Objects::nonNull ).collect( Collectors.toList() );
            ContextMemberProvider memberProvider = new StaticContextMemberProvider(members);
            IndexingContext mergedCtx = indexer.createMergedIndexingContext( tempRepoId, tempRepoId, mergedIndexDirectory.toFile(),
                indexLocation.toFile(), true, memberProvider);
            mergedCtx.optimize();

            if ( indexMergerRequest.isPackIndex() )
            {
                IndexPackingRequest request = new IndexPackingRequest( mergedCtx, //
                                                                       mergedCtx.acquireIndexSearcher().getIndexReader(), //
                                                                       indexLocation.toFile() );
                indexPacker.packIndex( request );
            }

            if ( indexMergerRequest.isTemporary() )
            {
                temporaryGroupIndexes.add( new TemporaryGroupIndex( mergedIndexDirectory, tempRepoId, groupId,
                                                                    indexMergerRequest.getMergedIndexTtl() ) );
                temporaryContextes.add(mergedCtx);
            }
            stopWatch.stop();
            log.info( "merged index for repos {} in {} s", indexMergerRequest.getRepositoriesIds(),
                      stopWatch.getTime() );
            return mergedCtx;
        }
        catch ( IOException e)
        {
            throw new IndexMergerException( e.getMessage(), e );
        }
        finally
        {
            runningGroups.remove( groupId );
        }
    }

    @Async
    @Override
    public void cleanTemporaryGroupIndex( TemporaryGroupIndex temporaryGroupIndex )
    {
        if ( temporaryGroupIndex == null )
        {
            return;
        }

        try
        {

            Optional<IndexingContext> ctxOpt = temporaryContextes.stream( ).filter( ctx -> ctx.getId( ).equals( temporaryGroupIndex.getIndexId( ) ) ).findFirst( );
            if (ctxOpt.isPresent()) {
                IndexingContext ctx = ctxOpt.get();
                indexer.closeIndexingContext( ctx, true );
                temporaryGroupIndexes.remove( temporaryGroupIndex );
                temporaryContextes.remove( ctx );
                Path directory = temporaryGroupIndex.getDirectory();
                if ( directory != null && Files.exists(directory) )
                {
                    FileUtils.deleteDirectory( directory );
                }
            }
        }
        catch ( IOException e )
        {
            log.warn( "fail to delete temporary group index {}", temporaryGroupIndex.getIndexId(), e );
        }
    }

    @Override
    public Collection<TemporaryGroupIndex> getTemporaryGroupIndexes()
    {
        return this.temporaryGroupIndexes;
    }
}
