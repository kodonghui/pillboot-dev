package org.semiproject.pillbadeumserver.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.service.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit public-data synchronization and regression check; no OpenAI, database or secret output. */
public final class PillCatalogCli {
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        int exit = 1;
        try {
            Properties local = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { local.load(reader); }
            ChatProviderCli.configureProxy();
            var json = new JsonMapper();
            var client = new PublicDataClient(json, "https://apis.data.go.kr",
                    ChatProviderCli.setting(local, "PUBLIC_DATA_SERVICE_KEY", ""), 2000, 5000, 4, 1000);
            try (var catalog = new PillCatalog(client, json, ".cache/pill-identification/catalog.json", false, 4)) {
                if (args.length == 1 && args[0].equals("refresh")) catalog.refresh();
                var clues = new PillIdentification(null, null, "DK", "10", "원형", "흰색", null, null, null);
                long start = System.nanoTime();
                var candidates = new PillDrugIdentificationService(client, catalog).identify(clues);
                System.out.println("catalogRows=" + catalog.rows().size() + " lookupMs=" + (System.nanoTime()-start)/1_000_000);
                for (var c : candidates) System.out.println(json.writeValueAsString(Map.of("rank", c.rank(),
                        "itemSeq", c.itemSeq(), "itemName", c.itemName(), "matchDetails", c.matchDetails())));
                boolean matched = !candidates.isEmpty() && candidates.getFirst().itemSeq().equals("197400040")
                        && candidates.getFirst().similarityScore() == null;
                var reversed = new PillIdentification("null", "null", "10", "DK", "원형", "백색", "null", "null", "null");
                matched &= new PillDrugIdentificationService(client, catalog).identify(reversed).getFirst().itemSeq().equals("197400040");
                System.out.println("indenolRegression=" + (matched ? "PASS" : "FAIL"));
                if (matched) exit = 0;
            }
        } catch (Exception error) {
            System.out.println("pillCatalog=FAILED type=" + error.getClass().getSimpleName());
            for (var frame : error.getStackTrace()) if (frame.getClassName().contains("image.service")) {
                System.out.println("location=" + frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber()); break;
            }
        }
        System.exit(exit);
    }
}
