package org.semiproject.pillbadeumserver.drug.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semiproject.pillbadeumserver.common.external.PublicDataClient;
import tools.jackson.databind.JsonNode;

/**
 * 2026-11-08 카톡 기록(처방전에 9자리 보험코드가 있는데 이름으로만 찾아 못 찾음): 처방전·대화의 9자리 보험코드(EDI 코드)로
 * 식약처 허가 상세정보(getDrugPrdtPrmsnDtlInq08, edi_code 조회 인자·EDI_CODE 응답 칸, 공공데이터포털 15095677 명세)를 찾는다.
 * 응답 행의 EDI_CODE 칸에 그 코드가 실제로 든 행만 쓴다. API가 인자를 무시하고 다른 약을 돌려줘도 채택하지 않는다.
 * 코드는 사진·글에 적힌 것만 쓰고 만들지 않는다. 찾기만 하고 저장하지 않는다.
 */
public final class InsuranceCodeLookup {
    /** 앞뒤가 숫자가 아닌 9자리 숫자. 보험코드는 9자리다. */
    public static final Pattern CODE = Pattern.compile("(?<![0-9])([0-9]{9})(?![0-9])");
    private static final int ROWS = 20;
    private final PublicDataClient client;

    public InsuranceCodeLookup(PublicDataClient client) { this.client = client; }

    /** 글에 적힌 첫 9자리 보험코드. 없으면 null. */
    public static String extract(String text) {
        if (text == null) return null;
        Matcher match = CODE.matcher(text.replaceAll("[\\s-]+(?=[0-9])", ""));
        return match.find() ? match.group(1) : null;
    }

    /**
     * 글에서 보험코드를 뺀 나머지(이름 부분). 띄어쓰기는 하나로 줄인다. extract가 띄어쓰기·'-'를 빼고 찾은 코드('6400-00001')도
     * 그 모양 그대로 뺀다(PR #77 Codex 리뷰: 이름 쪽에 남아 '00001mg' 함량으로 읽혔음).
     */
    public static String withoutCode(String text) {
        if (text == null) return "";
        String code = extract(text);
        String rest = code == null ? text : Pattern.compile("(?<![0-9])" + String.join("[\\s-]*", code.split("")) + "(?![0-9])")
                .matcher(text).replaceFirst(" ");
        return CODE.matcher(rest).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    /**
     * 그 보험코드가 든 허가 행. 통신 장애는 그대로 던진다(빈 결과로 숨기지 않음).
     * @param remaining 질문 전체 한도에서 남은 시간. null이면 공공데이터 기본 한도
     */
    public List<JsonNode> find(String code, Duration remaining) {
        if (code == null || !code.matches("[0-9]{9}")) return List.of();
        var params = new LinkedHashMap<String, Object>();
        params.put("edi_code", code);
        params.put("pageNo", 1);
        params.put("numOfRows", ROWS);
        var body = remaining == null ? client.get(DrugSyncService.PERMIT_INFO_PATH, params)
                : client.get(DrugSyncService.PERMIT_INFO_PATH, params, remaining);
        return PublicDataClient.items(body).stream().filter(row -> codes(text(row, "EDI_CODE")).contains(code)).toList();
    }

    /** EDI_CODE 칸의 9자리 코드들. 쉼표·공백 등으로 여러 개가 올 수 있다. */
    static Set<String> codes(String field) {
        var found = new LinkedHashSet<String>();
        if (field == null) return found;
        Matcher match = CODE.matcher(field);
        while (match.find()) found.add(match.group(1));
        return found;
    }

    private static String text(JsonNode row, String field) {
        for (var property : row.properties())
            if (property.getKey().equalsIgnoreCase(field) && !property.getValue().isNull())
                return property.getValue().asString("");
        return null;
    }
}
