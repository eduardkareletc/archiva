package org.apache.maven.archiva.reporting;

/*
 * Copyright 2005-2006 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.codehaus.plexus.PlexusTestCase;

import java.io.File;

/**
 *
 */
public abstract class AbstractRepositoryReportsTestCase
    extends PlexusTestCase
{
    /**
     * This should only be used for the few that can't use the query layer.
     */
    protected ArtifactRepository repository;

    private ArtifactFactory artifactFactory;

    private ArtifactRepositoryFactory factory;

    private ArtifactRepositoryLayout layout;

    protected void setUp()
        throws Exception
    {
        super.setUp();

        File repositoryDirectory = getTestFile( "src/test/repository" );

        factory = (ArtifactRepositoryFactory) lookup( ArtifactRepositoryFactory.ROLE );
        layout = (ArtifactRepositoryLayout) lookup( ArtifactRepositoryLayout.ROLE, "default" );

        repository = factory.createArtifactRepository( "repository", repositoryDirectory.toURL().toString(), layout,
                                                       null, null );
        artifactFactory = (ArtifactFactory) lookup( ArtifactFactory.ROLE );
    }

    protected Artifact createArtifactFromRepository( File repository, String groupId, String artifactId,
                                                     String version )
        throws Exception
    {
        Artifact artifact = artifactFactory.createBuildArtifact( groupId, artifactId, version, "jar" );

        artifact.setRepository(
            factory.createArtifactRepository( "repository", repository.toURL().toString(), layout, null, null ) );

        artifact.isSnapshot();

        return artifact;
    }

    protected Artifact createArtifact( String groupId, String artifactId, String version )
    {
        return createArtifact( groupId, artifactId, version, "jar" );
    }

    protected Artifact createArtifact( String groupId, String artifactId, String version, String type )
    {
        Artifact artifact = artifactFactory.createBuildArtifact( groupId, artifactId, version, type );
        artifact.setRepository( repository );
        artifact.isSnapshot();
        return artifact;
    }

    protected Artifact createArtifactWithClassifier( String groupId, String artifactId, String version, String type,
                                                     String classifier )
    {
        Artifact artifact =
            artifactFactory.createArtifactWithClassifier( groupId, artifactId, version, type, classifier );
        artifact.setRepository( repository );
        return artifact;
    }

}
