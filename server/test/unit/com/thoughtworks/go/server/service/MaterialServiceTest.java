/*************************GO-LICENSE-START*********************************
 * Copyright 2014 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *************************GO-LICENSE-END***********************************/

package com.thoughtworks.go.server.service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.thoughtworks.go.config.CaseInsensitiveString;
import com.thoughtworks.go.config.materials.PackageMaterial;
import com.thoughtworks.go.domain.packagerepository.PackageRepositoryMother;
import com.thoughtworks.go.config.materials.SubprocessExecutionContext;
import com.thoughtworks.go.config.materials.dependency.DependencyMaterial;
import com.thoughtworks.go.config.materials.git.GitMaterial;
import com.thoughtworks.go.config.materials.mercurial.HgMaterial;
import com.thoughtworks.go.config.materials.perforce.P4Material;
import com.thoughtworks.go.config.materials.svn.SvnMaterial;
import com.thoughtworks.go.config.materials.tfs.TfsMaterial;
import com.thoughtworks.go.domain.MaterialRevision;
import com.thoughtworks.go.domain.MaterialRevisions;
import com.thoughtworks.go.domain.materials.MatchedRevision;
import com.thoughtworks.go.domain.materials.Material;
import com.thoughtworks.go.domain.materials.MaterialConfig;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.domain.materials.Revision;
import com.thoughtworks.go.domain.materials.packagematerial.PackageMaterialRevision;
import com.thoughtworks.go.domain.config.Configuration;
import com.thoughtworks.go.domain.packagerepository.PackageDefinition;
import com.thoughtworks.go.i18n.LocalizedMessage;
import com.thoughtworks.go.security.GoCipher;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.server.service.result.LocalizedOperationResult;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.HealthStateType;
import com.thoughtworks.go.plugin.api.material.packagerepository.PackageMaterialProvider;
import com.thoughtworks.go.plugin.api.material.packagerepository.PackageRevision;
import com.thoughtworks.go.plugin.infra.ActionWithReturn;
import com.thoughtworks.go.plugin.infra.PluginManager;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import static com.thoughtworks.go.domain.packagerepository.PackageDefinitionMother.create;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(Theories.class)
public class MaterialServiceTest {
    private MaterialService materialService;
    private MaterialRepository materialRepository;
    private GoConfigService goConfigService;
    private SecurityService securityService;

    private static List MODIFICATIONS = new ArrayList<Modification>();
    private PluginManager pluginManager;

    @Before
    public void setUp() {
        materialRepository = mock(MaterialRepository.class);
        goConfigService = mock(GoConfigService.class);
        securityService = mock(SecurityService.class);
        pluginManager = mock(PluginManager.class);
        materialService = new MaterialService(materialRepository, goConfigService, securityService, pluginManager);
    }

    @Test
    public void shouldUnderstandIfMaterialHasModifications() {
        assertHasModifcation(new MaterialRevisions(new MaterialRevision(new HgMaterial("foo.com", null), new Modification(new Date(), "2", "MOCK_LABEL-12", null))), true);
        assertHasModifcation(new MaterialRevisions(), false);
    }

    @Test
    public void shouldNotBeAuthorizedToViewAPipeline() {
        when(securityService.hasViewPermissionForPipeline("pavan", "pipeline")).thenReturn(false);
        LocalizedOperationResult operationResult = mock(LocalizedOperationResult.class);
        materialService.searchRevisions("pipeline", "sha", "search-string", new Username(new CaseInsensitiveString("pavan")), operationResult);
        verify(operationResult).unauthorized(LocalizedMessage.cannotViewPipeline("pipeline"), HealthStateType.general(HealthStateScope.forPipeline("pipeline")));
    }

    @Test
    public void shouldReturnTheRevisionsThatMatchTheGivenSearchString() {
        when(securityService.hasViewPermissionForPipeline("pavan", "pipeline")).thenReturn(true);
        LocalizedOperationResult operationResult = mock(LocalizedOperationResult.class);
        MaterialConfig materialConfig = mock(MaterialConfig.class);
        when(goConfigService.materialForPipelineWithFingerprint("pipeline", "sha")).thenReturn(materialConfig);

        List<MatchedRevision> expected = Arrays.asList(new MatchedRevision("23", "revision", "revision", "user", new DateTime(2009, 10, 10, 12, 0, 0, 0).toDate(), "comment"));
        when(materialRepository.findRevisionsMatching(materialConfig, "23")).thenReturn(expected);
        assertThat(materialService.searchRevisions("pipeline", "sha", "23", new Username(new CaseInsensitiveString("pavan")), operationResult), is(expected));
    }

    @Test
    public void shouldReturnNotFoundIfTheMaterialDoesNotBelongToTheGivenPipeline() {
        when(securityService.hasViewPermissionForPipeline("pavan", "pipeline")).thenReturn(true);
        LocalizedOperationResult operationResult = mock(LocalizedOperationResult.class);

        when(goConfigService.materialForPipelineWithFingerprint("pipeline", "sha")).thenThrow(new RuntimeException("Not found"));

        materialService.searchRevisions("pipeline", "sha", "23", new Username(new CaseInsensitiveString("pavan")), operationResult);
        verify(operationResult).notFound(LocalizedMessage.materialWithFingerPrintNotFound("pipeline", "sha"), HealthStateType.general(HealthStateScope.forPipeline("pipeline")));
    }

    @DataPoint public static RequestDataPoints GIT_LATEST_MODIFICATIONS = new RequestDataPoints(new GitMaterial("url") {
        @Override
        public List<Modification> latestModification(File baseDir, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

        @Override
        public List<Modification> modificationsSince(File baseDir, Revision revision, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }
    }, GitMaterial.class);

    @DataPoint public static RequestDataPoints SVN_LATEST_MODIFICATIONS = new RequestDataPoints(new SvnMaterial("url", "username", "password", true) {
        @Override
        public List<Modification> latestModification(File baseDir, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

        @Override
        public List<Modification> modificationsSince(File baseDir, Revision revision, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }
    }, SvnMaterial.class);

    @DataPoint public static RequestDataPoints HG_LATEST_MODIFICATIONS = new RequestDataPoints(new HgMaterial("url", null) {
        @Override
        public List<Modification> latestModification(File baseDir, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

        @Override
        public List<Modification> modificationsSince(File baseDir, Revision revision, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }
    }, HgMaterial.class);

    @DataPoint public static RequestDataPoints TFS_LATEST_MODIFICATIONS = new RequestDataPoints(new TfsMaterial(mock(GoCipher.class)) {
        @Override
        public List<Modification> latestModification(File baseDir, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

        @Override
        public List<Modification> modificationsSince(File baseDir, Revision revision, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

    }, TfsMaterial.class);

    @DataPoint public static RequestDataPoints P4_LATEST_MODIFICATIONS = new RequestDataPoints(new P4Material("url", "view", "user") {
        @Override
        public List<Modification> latestModification(File baseDir, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

        @Override
        public List<Modification> modificationsSince(File baseDir, Revision revision, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }
    }, P4Material.class);

    @DataPoint public static RequestDataPoints DEPENDENCY_LATEST_MODIFICATIONS = new RequestDataPoints(new DependencyMaterial(new CaseInsensitiveString("p1"), new CaseInsensitiveString("s1")) {
        @Override
        public List<Modification> latestModification(File baseDir, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }

        @Override
        public List<Modification> modificationsSince(File baseDir, Revision revision, SubprocessExecutionContext execCtx) {
            return (List<Modification>) MODIFICATIONS;
        }
    }, DependencyMaterial.class);


    @Theory
    public void shouldGetLatestModificationsForGivenMaterial(RequestDataPoints data) {
        MaterialService spy = spy(materialService);
        doReturn(data.klass).when(spy).getMaterialClass(data.material);
        List<Modification> actual = spy.latestModification(data.material, null, null);
        assertThat(actual, is(MODIFICATIONS));
    }

    @Theory
    public void shouldGetModificationsSinceARevisionForGivenMaterial(RequestDataPoints data) {
        Revision revision = mock(Revision.class);
        MaterialService spy = spy(materialService);
        doReturn(data.klass).when(spy).getMaterialClass(data.material);
        List<Modification> actual = spy.modificationsSince(data.material, null, revision, null);
        assertThat(actual, is(MODIFICATIONS));
    }

    @Test
    public void shouldThrowExceptionWhenPollerForMaterialNotFound() {
        try {
            materialService.latestModification(mock(Material.class), null, null);
            fail("Should have thrown up");
        } catch (RuntimeException e) {
            assertThat(e.getMessage(), is("unknown material type null"));
        }
    }

    @Test
    public void shouldGetLatestModificationForPackageMaterial() {
        PackageMaterial material = new PackageMaterial();
        PackageDefinition packageDefinition = create("id", "package", new Configuration(), PackageRepositoryMother.create("id", "name", "plugin-id", "plugin-version", new Configuration()));
        material.setPackageDefinition(packageDefinition);


        when(pluginManager.doOn(eq(PackageMaterialProvider.class), eq("plugin-id"), any(ActionWithReturn.class))).thenReturn(new PackageRevision("blah-123", new Date(), "user"));

        List<Modification> modifications = materialService.latestModification(material, null, null);
        assertThat(modifications.get(0).getRevision(), is("blah-123"));
    }

    @Test
    public void shouldGetModificationSinceAGivenRevision() {
        PackageMaterial material = new PackageMaterial();
        PackageDefinition packageDefinition = create("id", "package", new Configuration(), PackageRepositoryMother.create("id", "name", "plugin-id", "plugin-version", new Configuration()));
        material.setPackageDefinition(packageDefinition);

        when(pluginManager.doOn(eq(PackageMaterialProvider.class), eq("plugin-id"), any(ActionWithReturn.class))).thenReturn(new PackageRevision("new-revision-456", new Date(), "user"));
        List<Modification> modifications = materialService.modificationsSince(material, null, new PackageMaterialRevision("revision-124", new Date()), null);
        assertThat(modifications.get(0).getRevision(), is("new-revision-456"));
    }

    private void assertHasModifcation(MaterialRevisions materialRevisions, boolean b) {
        HgMaterial hgMaterial = new HgMaterial("foo.com", null);
        when(materialRepository.findLatestModification(hgMaterial)).thenReturn(materialRevisions);
        assertThat(materialService.hasModificationFor(hgMaterial), is(b));
    }

    private static class RequestDataPoints<T extends Material> {
        final T material;
        final Class klass;

        public RequestDataPoints(T material, Class klass) {
            this.material = material;
            this.klass = klass;
        }
    }

}
