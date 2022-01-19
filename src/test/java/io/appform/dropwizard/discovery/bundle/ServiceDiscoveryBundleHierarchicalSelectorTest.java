/*
 * Copyright (c) 2019 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.appform.dropwizard.discovery.bundle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.appform.dropwizard.discovery.bundle.selectors.HierarchicalEnvironmentAwareShardSelector;
import io.appform.dropwizard.discovery.bundle.selectors.HierarchicalSelectionPredicate;
import io.appform.ranger.common.server.ShardInfo;
import io.appform.ranger.core.finder.serviceregistry.MapBasedServiceRegistry;
import io.appform.ranger.core.healthcheck.HealthcheckStatus;
import io.appform.ranger.core.model.ShardSelector;
import io.dropwizard.Configuration;
import io.dropwizard.jersey.DropwizardResourceConfig;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.lifecycle.setup.LifecycleEnvironment;
import io.dropwizard.setup.AdminEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.curator.test.TestingCluster;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

import static io.appform.dropwizard.discovery.bundle.TestUtils.assertNodeAbsence;
import static io.appform.dropwizard.discovery.bundle.TestUtils.assertNodePresence;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@Slf4j
class ServiceDiscoveryBundleHierarchicalSelectorTest {

    private final HealthCheckRegistry healthChecks = mock(HealthCheckRegistry.class);
    private final JerseyEnvironment jerseyEnvironment = mock(JerseyEnvironment.class);
    private final MetricRegistry metricRegistry = mock(MetricRegistry.class);
    private final LifecycleEnvironment lifecycleEnvironment = new LifecycleEnvironment(metricRegistry);
    private final Environment environment = mock(Environment.class);
    private final Bootstrap<?> bootstrap = mock(Bootstrap.class);
    private Configuration configuration;

    static {
        val root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
    }

    private static final class TestConfig extends Configuration {
        @Getter
        private final ServiceDiscoveryConfiguration configuration;

        private TestConfig(ServiceDiscoveryConfiguration configuration) {
            this.configuration = configuration;
        }
    }

    private final ServiceDiscoveryBundle<TestConfig> bundle = new ServiceDiscoveryBundle<TestConfig>() {
        @Override
        protected ShardSelector<ShardInfo, MapBasedServiceRegistry<ShardInfo>> getShardSelector(
                TestConfig configuration) {
            return new HierarchicalEnvironmentAwareShardSelector();
        }

        @Override
        protected ServiceDiscoveryConfiguration getRangerConfiguration(TestConfig configuration) {
            return serviceDiscoveryConfiguration;
        }

        @Override
        protected String getServiceName(TestConfig configuration) {
            return "TestService";
        }

        @Override
        protected Predicate<ShardInfo> getInitialCriteria(TestConfig configuration) {
            return new HierarchicalSelectionPredicate(ShardInfo.builder()
                                                              .environment(configuration.getConfiguration()
                                                                                   .getEnvironment())
                                                              .build());
        }

        @Override
        protected boolean alwaysMergeWithInitialCriteria(TestConfig configuration) {
            return true;
        }


    };

    private ServiceDiscoveryConfiguration serviceDiscoveryConfiguration;
    private final TestingCluster testingCluster = new TestingCluster(1);
    private HealthcheckStatus status = HealthcheckStatus.healthy;

    @BeforeEach
    void setup() throws Exception {
        when(jerseyEnvironment.getResourceConfig()).thenReturn(new DropwizardResourceConfig());
        when(environment.jersey()).thenReturn(jerseyEnvironment);
        when(environment.lifecycle()).thenReturn(lifecycleEnvironment);
        when(environment.healthChecks()).thenReturn(healthChecks);
        when(environment.getObjectMapper()).thenReturn(new ObjectMapper());
        AdminEnvironment adminEnvironment = mock(AdminEnvironment.class);
        doNothing().when(adminEnvironment).addTask(any());
        when(environment.admin()).thenReturn(adminEnvironment);

        testingCluster.start();

        serviceDiscoveryConfiguration = ServiceDiscoveryConfiguration.builder()
                .zookeeper(testingCluster.getConnectString())
                .namespace("test")
                .environment("x.y")
                .connectionRetryIntervalMillis(5000)
                .publishedHost("TestHost")
                .publishedPort(8021)
                .initialRotationStatus(true)
                .build();
        bundle.initialize(bootstrap);
        bundle.run(new TestConfig(serviceDiscoveryConfiguration), environment);
        bundle.getServerStatus().markStarted();
        for (LifeCycle lifeCycle : lifecycleEnvironment.getManagedObjects()) {
            lifeCycle.start();
        }
        bundle.registerHealthcheck(() -> status);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (LifeCycle lifeCycle : lifecycleEnvironment.getManagedObjects()) {
            lifeCycle.stop();
        }
        testingCluster.stop();
    }

    @Test
    void testDiscovery() {
        assertNodePresence(bundle);
        val info = bundle.getServiceDiscoveryClient()
                .getNode()
                .orElse(null);
        Assertions.assertNotNull(info);
        Assertions.assertNotNull(info.getNodeData());
        Assertions.assertEquals("x.y", info.getNodeData().getEnvironment());
        Assertions.assertEquals("TestHost", info.getHost());
        Assertions.assertEquals(8021, info.getPort());

        status = HealthcheckStatus.unhealthy;

        assertNodeAbsence(bundle);
    }
}