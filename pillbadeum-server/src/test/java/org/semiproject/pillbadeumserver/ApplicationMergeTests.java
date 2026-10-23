package org.semiproject.pillbadeumserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 병합 후 전체 빈과 API 매핑이 함께 구성되는지 검사한다. 실제 DB와 외부 API는 사용하지 않는다. */
@SpringBootTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationMergeTests {
    @MockitoBean DataSource dataSource;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;

    @Test
    void medicationChoicesAndTeammatesDrugListHaveDifferentRoutesAndSwaggerLoads() throws Exception {
        var paths = mappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()).toList();
        assertThat(paths).contains("/api/profiles/{profileId}/drugs", "/api/profiles/{profileId}/drugs/schedule-choices",
                "/api/profiles/{profileId}/drugs/{myDrugId}/schedule");
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void swaggerKeepsMedicationDetailsAndCandidateSelectionAsDifferentItems() throws Exception {
        var docs = new tools.jackson.databind.json.JsonMapper().readTree(
                mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        var schemas = docs.path("components").path("schemas");
        var request = schemas.path("MyDrugCreateRequest").path("properties");
        var drugsRef = request.path("drugs").path("items").path("$ref").asString();
        var choicesRef = request.path("items").path("items").path("$ref").asString();
        assertThat(drugsRef).isNotBlank().isNotEqualTo(choicesRef);
        assertThat(choicesRef).isNotBlank();
        var drugs = schemas.path(drugsRef.substring(drugsRef.lastIndexOf('/') + 1)).path("properties");
        var choices = schemas.path(choicesRef.substring(choicesRef.lastIndexOf('/') + 1)).path("properties");
        assertThat(drugs.has("itemSeq") && drugs.has("dosePerTime") && drugs.has("startDate")).isTrue();
        assertThat(choices.has("itemSeq") && choices.has("source") && choices.has("readNo")).isTrue();
        assertThat(choices.has("dosePerTime")).isFalse();
    }
}
