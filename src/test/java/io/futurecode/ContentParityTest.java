package io.futurecode;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ContentParityTest {
    @Test void mavenBuildRejectsStaleBrowserContentWithoutNeedingNodeAtRuntime() throws Exception {
        CuratedContent content = new CuratedContent();
        for (var source : content.manifest().path("browserSourceHashes").properties()) {
            String text = Files.readString(Path.of("public", source.getKey())).replace("\r\n", "\n").replace('\r', '\n');
            assertEquals(source.getValue().asString(), Crypto.sha(text), "Browser content changed; run node scripts\\export-content.mjs before packaging.");
        }
        assertEquals(3, content.manifest().path("browserSourceHashes").size());
        for (var challenge : content.challenges()) {
            String key = "drill:" + Json.text(challenge, "id");
            assertEquals(Json.text(content.manifest().path("versions"), key), Crypto.sha(Json.stringify(challenge)));
        }
        assertEquals(Json.text(content.manifest().path("versions"), "case:missing-requests-v1"), Crypto.sha(Json.stringify(content.practiceCase().path("files"))));
    }
}
