package com.lx.governance.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class ProductSearchRoutesTest {
    @Test
    void routesProductAndSearchApisToTheirServicesAndStripsOnlyApiPrefix() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertRoute(sources, "search", "${SEARCH_SERVICE_URI:lb://search-service}", "Path=/api/search/**");
        assertRoute(sources, "product", "${PRODUCT_SERVICE_URI:lb://product-service}", "Path=/api/product/**");
    }

    private void assertRoute(List<PropertySource<?>> sources, String id, String uri, String predicate) {
        PropertySource<?> source = sources.get(0);
        for (int index = 0; index < 100; index++) {
            String prefix = "spring.cloud.gateway.routes[" + index + "]";
            if (id.equals(source.getProperty(prefix + ".id"))) {
                assertThat(source.getProperty(prefix + ".uri")).isEqualTo(uri);
                assertThat(source.getProperty(prefix + ".predicates[0]")).isEqualTo(predicate);
                assertThat(source.getProperty(prefix + ".filters[0]")).isEqualTo("StripPrefix=2");
                return;
            }
        }
        throw new AssertionError("缺少显式路由：" + id);
    }
}
