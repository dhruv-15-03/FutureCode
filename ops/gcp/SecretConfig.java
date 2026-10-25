import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

public final class SecretConfig {
    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException();
            Properties local = new Properties();
            try (var input = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
                local.load(input);
            }
            Properties cloud = new Properties();
            for (String name : new String[]{"DATABASE_URL", "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET"}) {
                String value = local.getProperty(name, "");
                if (value.isBlank() || value.length() > 8192 || value.contains("\n") || value.contains("\r")) {
                    throw new IllegalArgumentException();
                }
                cloud.setProperty(name, value);
            }
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            cloud.setProperty("SESSION_SECRET", HexFormat.of().formatHex(random));
            StringWriter output = new StringWriter();
            cloud.store(output, "FutureCode production-only credentials; never print or commit.");
            System.out.print(output);
        } catch (java.io.IOException | IllegalArgumentException | SecurityException error) {
            System.err.println("Private deployment configuration is invalid; no values were displayed.");
            System.exit(1);
        }
    }
}
