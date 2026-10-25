package io.futurecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class SchemaSettingsTest {
    @Test void explicitSchemaDoesNotRewriteOrExposeConnectionCredentials() {
        ObjectNode value = (ObjectNode) Json.node(TestData.settings(true));
        value.put("databaseSchema", "FutureTool");
        AppSettings settings = Json.MAPPER.treeToValue(value, AppSettings.class);
        assertEquals("FutureTool", settings.database().schema());
        assertEquals(TestData.settings(true).databaseUrl(), settings.databaseUrl());
        assertFalse(settings.toString().contains("unused"));
        assertFalse(settings.database().toString().contains("unused"));
    }
    @Test void namedSchemaCanBeProvisionedWithoutAnExistingDatabasePath() {
        ObjectNode value = (ObjectNode) Json.node(TestData.settings(true));
        value.put("databaseSchema", "FutureTool").put("databaseUrl", "mysql://fixture:unused@127.0.0.1");
        assertEquals("FutureTool", Json.MAPPER.treeToValue(value, AppSettings.class).database().schema());
    }
    @Test void explicitlyEmptyOverrideKeepsDedicatedTestAndLegacySchemas() {
        assertEquals("futurecode_test", TestData.settings(true).database().schema());
    }
    @ParameterizedTest @ValueSource(strings = {"a`", "x;DROP DATABASE other", "../db", "with space", ""})
    void schemaIdentifiersCannotInjectOrEscapeDatabaseStatements(String value) {
        assertThrows(IllegalArgumentException.class, () -> DatabaseSetup.identifier(value));
    }
    @Test void schemaNamesHaveTheMySqlLengthBound() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseSetup.identifier("x".repeat(65)));
    }
    @Test void provisioningIsAnExplicitNonServerCommand() {
        ObjectNode value = (ObjectNode) Json.node(TestData.settings(true));
        value.put("command", "provision").put("databaseSchema", "FutureTool");
        assertEquals("provision", Json.MAPPER.treeToValue(value, AppSettings.class).command());
        assertEquals("`FutureTool`", DatabaseSetup.identifier("FutureTool"));
    }
    @Test void loginConfigurationCheckReturnsOnlyFieldNamesAndNeverValues() {
        assertTrue(TestData.settings(true).loginConfigurationIssues().isEmpty());
        var issues = TestData.settings(false).loginConfigurationIssues();
        assertEquals(java.util.List.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "SESSION_SECRET"), issues);
    }
    @ParameterizedTest @ValueSource(strings = {"ssl-mode=REQUIRED", "sslMode=VERIFY_IDENTITY", "ssl-mode=VERIFY_CA"})
    void providerTlsOptionsAlwaysRetainVerifiedIdentity(String query) {
        ObjectNode value = (ObjectNode) Json.node(TestData.settings(true));
        value.put("databaseUrl", TestData.settings(true).databaseUrl() + "?" + query).put("mysqlTls", "required");
        assertTrue(Json.MAPPER.treeToValue(value, AppSettings.class).database().tls());
    }
    @ParameterizedTest @ValueSource(strings = {"ssl=false", "ssl-mode=DISABLED", "sslMode=PREFERRED", "ssl-mode=REQUIRED&allowPublicKeyRetrieval=true", "user=override"})
    void URLParametersCannotDisableTlsOrInjectDriverSettings(String query) {
        assertFalse(AppSettings.tlsQueryAccepted(query));
    }
}
