package org.semiproject.pillbadeumserver.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** CHAT-004 수동 설정 점검. 정상 실행과 같은 .env/환경변수를 읽고 설정 유무만 출력하며 외부 호출·DB 쓰기는 없다. */
public final class ChatConfigurationCli {
    private ChatConfigurationCli() {}

    public static void main(String[] args) throws Exception {
        Properties local = new Properties();
        if (Files.isRegularFile(Path.of(".env"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
                local.load(reader);
            }
        }
        for (String name : new String[] {"OPENAI_API_KEY", "OPENAI_DECISION_MODEL", "OPENAI_CHAT_MODEL",
                "PUBLIC_DATA_SERVICE_KEY"}) {
            String value = setting(local, name, "");
            System.out.println(name + "=" + (value.isBlank() ? "MISSING" : "CONFIGURED"));
        }
        System.out.println("QDRANT_ENABLED=" + Boolean.parseBoolean(setting(local, "QDRANT_ENABLED", "false")));
        for(String name:new String[]{"OPENAI_CHAT_MODEL","PUBLIC_DATA_SERVICE_KEY"}) {
            String value=setting(local,name,"").strip();
            System.out.println(name+"_OUTER_QUOTES="+(value.length()>1&&((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'")))));
        }
        String publicKey=setting(local,"PUBLIC_DATA_SERVICE_KEY","");
        System.out.println("PUBLIC_DATA_KEY_WHITESPACE="+publicKey.chars().anyMatch(Character::isWhitespace));
        System.out.println("PUBLIC_DATA_KEY_CREDENTIAL_ALPHABET="+(!publicKey.isBlank()&&publicKey.matches("[A-Za-z0-9+/=_-]+")));
        System.out.println("키·모델의 실제 권한과 연결 성공은 이 점검 결과에 포함하지 않습니다.");
    }

    private static String setting(Properties local, String name, String fallback) {
        String environment = System.getenv(name);
        return environment != null ? environment : local.getProperty(name, fallback);
    }
}
