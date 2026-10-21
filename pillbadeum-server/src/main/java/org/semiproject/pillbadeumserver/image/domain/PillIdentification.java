package org.semiproject.pillbadeumserver.image.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.semiproject.pillbadeumserver.common.exception.BusinessException;
import org.semiproject.pillbadeumserver.common.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

/** REG-007: 사진 순서의 관찰값임. 공식 앞뒤와 구분하며 빈 값은 무각인으로 추정하지 않음. */
@Schema(description = "읽힌 각인 한 면·모양·색상은 필수이며 없음과 미판독을 구분합니다.")
public record PillIdentification(
    String itemName, String entpName, String printFront, String printBack,
    String shape, String colorClass1, String colorClass2, String lineFront, String lineBack,
    ObservationState printFrontState, ObservationState printBackState,
    ObservationState lineFrontState, ObservationState lineBackState) {

  public enum ObservationState { READABLE, NONE, UNREADABLE }

  /** 기존 문자열 요청의 호환 진입임. 비어 있는 면은 UNREADABLE임. */
  public PillIdentification(String itemName, String entpName, String printFront, String printBack,
      String shape, String colorClass1, String colorClass2, String lineFront, String lineBack) {
    this(itemName, entpName, printFront, printBack, shape, colorClass1, colorClass2,
        lineFront, lineBack, null, null, null, null);
  }

  public PillIdentification {
    itemName = clean(itemName, 200); entpName = clean(entpName, 200);
    printFront = clean(printFront, 50); printBack = clean(printBack, 50);
    shape = clean(shape, 50); colorClass1 = clean(colorClass1, 50); colorClass2 = clean(colorClass2, 50);
    lineFront = clean(lineFront, 10); lineBack = clean(lineBack, 10);
    printFrontState = state(printFrontState, printFront);
    printBackState = state(printBackState, printBack);
    // 문자 일부/기호만은 읽힌 각인으로 쓰지 않음. O/0 등의 자동 치환은 하지 않음.
    if (printFrontState == ObservationState.READABLE && !legible(printFront)) {
      printFrontState = ObservationState.UNREADABLE; printFront = null;
    }
    if (printBackState == ObservationState.READABLE && !legible(printBack)) {
      printBackState = ObservationState.UNREADABLE; printBack = null;
    }
    if (lineFrontState == null && "없음".equals(lineFront)) { lineFrontState = ObservationState.NONE; lineFront = null; }
    if (lineBackState == null && "없음".equals(lineBack)) { lineBackState = ObservationState.NONE; lineBack = null; }
    lineFrontState = state(lineFrontState, lineFront);
    lineBackState = state(lineBackState, lineBack);
    if (lineFrontState == ObservationState.READABLE) lineFront = splitLine(lineFront);
    if (lineBackState == ObservationState.READABLE) lineBack = splitLine(lineBack);
  }

  private static ObservationState state(ObservationState state, String value) {
    if (state == null) return value == null ? ObservationState.UNREADABLE : ObservationState.READABLE;
    if ((state == ObservationState.READABLE) != (value != null))
      throw new BusinessException(ErrorCode.INVALID_INPUT, "읽힘 상태와 각인·분할선 값을 확인해 주세요.");
    return state;
  }

  private static boolean legible(String value) {
    return value != null && value.matches("(?s).*[\\p{L}\\p{N}].*")
        && !value.matches("(?s).*[?？□�].*");
  }

  private static String splitLine(String value) {
    return switch (value.toUpperCase(java.util.Locale.ROOT)) {
      case "-", "MINUS", "일자", "일자형" -> "MINUS";
      case "+", "PLUS", "십자", "십자형" -> "PLUS";
      default -> throw new BusinessException(ErrorCode.INVALID_INPUT, "분할선은 MINUS·PLUS 또는 없음/미판독으로 입력해 주세요.");
    };
  }

  private static String clean(String value, int max) {
    if (value == null) return null;
    if (value.length() > max) throw new BusinessException(ErrorCode.INVALID_INPUT, "식별 단서의 길이를 확인해 주세요.");
    String s = value.strip();
    return s.isEmpty() || java.util.Set.of("NULL", "UNDEFINED", "UNKNOWN", "미확인", "모름").contains(s.toUpperCase(java.util.Locale.ROOT)) ? null : s;
  }

  public static PillIdentification fromVision(JsonNode n) {
    if (n == null || !n.isObject()) throw new BusinessException(ErrorCode.INVALID_INPUT, "식별 단서를 JSON 객체로 보내 주세요.");
    return new PillIdentification(value(n, "itemName"), value(n, "entpName"),
        value(n, "printFront"), value(n, "printBack"), value(n, "shape"),
        value(n, "colorClass1"), value(n, "colorClass2"), value(n, "lineFront"), value(n, "lineBack"),
        observation(n, "printFrontState"), observation(n, "printBackState"),
        observation(n, "lineFrontState"), observation(n, "lineBackState"));
  }

  private static String value(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    if (!v.isString()) throw new BusinessException(ErrorCode.INVALID_INPUT, "식별 단서는 문자열이어야 합니다.");
    return v.asString();
  }
  private static ObservationState observation(JsonNode n, String field) {
    String s = value(n, field);
    if (s == null) return null;
    try { return ObservationState.valueOf(s); }
    catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.INVALID_INPUT, "각인 상태를 확인해 주세요."); }
  }
}
