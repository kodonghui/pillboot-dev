package org.semiproject.pillbadeumserver.image.service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;
import org.semiproject.pillbadeumserver.image.domain.PillIdentification;
import org.semiproject.pillbadeumserver.image.domain.PillMatchDetail;
import static org.semiproject.pillbadeumserver.image.domain.PillIdentification.ObservationState.*;
import tools.jackson.databind.JsonNode;

/** REG-007: 필수 단서·명백한 모순을 검사하고 실제 각인/분할선 일치 수로만 순서를 정함. */
public final class PillSimilarity {
  private PillSimilarity() {}

  public record Match(JsonNode item, int exactImprints, int exactLines,
      String orientation, Boolean printMatched, List<PillMatchDetail> matchDetails) {}

  public static final Comparator<Match> ORDER = Comparator.comparingInt(Match::exactImprints).reversed()
      .thenComparing(Comparator.comparingInt(Match::exactLines).reversed());

  public static boolean hasCriteria(PillIdentification c) {
    return c != null && (c.printFrontState() == READABLE || c.printBackState() == READABLE)
        && present(c.shape()) && (present(c.colorClass1()) || present(c.colorClass2()));
  }

  /** 모양/색은 같은 공식 목록에 있는 정규화 값만 유효한 필수값으로 인정함. */
  public static List<String> missingFields(PillIdentification c, List<JsonNode> rows) {
    var result = new ArrayList<String>();
    if (c == null || (c.printFrontState() != READABLE && c.printBackState() != READABLE)) result.add("IMPRINT");
    var shapes = new HashSet<String>();
    var knownColors = new HashSet<String>();
    for (var row : rows) {
      shapes.add(shape(text(row, "DRUG_SHAPE")));
      knownColors.addAll(colors(text(row, "COLOR_CLASS1"), text(row, "COLOR_CLASS2")));
    }
    if (c == null || !present(c.shape()) || !shapes.contains(shape(c.shape()))) result.add("SHAPE");
    var observed = c == null ? Set.<String>of() : colors(c.colorClass1(), c.colorClass2());
    if (observed.isEmpty() || !knownColors.containsAll(observed)) result.add("COLOR");
    return List.copyOf(result);
  }

  /** 행별 최선 대응임. 호출자가 공식 필수 메타데이터 검증 후 품목 중복과 반환 상한을 적용함. */
  public static List<Match> rank(PillIdentification c, List<JsonNode> rows) {
    if (!missingFields(c, rows).isEmpty()) return List.of();
    var matches = new ArrayList<Match>();
    for (var row : rows) {
      Match direct = compare(c, row, false), swapped = compare(c, row, true);
      Match best = direct == null ? swapped : swapped == null || ORDER.compare(direct, swapped) <= 0 ? direct : swapped;
      if (best != null) matches.add(best);
    }
    return matches.stream().sorted(ORDER
        .thenComparing(m -> Objects.toString(text(m.item(), "ITEM_SEQ"), ""))
        .thenComparingInt(m -> "DIRECT".equals(m.orientation()) ? 0 : 1)).toList();
  }

  public static Boolean printsMatch(PillIdentification c, JsonNode row) {
    var a = compare(c, row, false); var b = compare(c, row, true);
    var best = a == null ? b : b == null || ORDER.compare(a, b) <= 0 ? a : b;
    return best == null ? Boolean.FALSE : best.printMatched();
  }

  private static Match compare(PillIdentification c, JsonNode row, boolean reverse) {
    if (!hasCriteria(c)) return null;
    String front = reverse ? "Back" : "Front", back = reverse ? "Front" : "Back";
    var details = new ArrayList<PillMatchDetail>();
    int imprints = 0, lines = 0;
    String[] observedPrints = {c.printFront(), c.printBack()};
    String[] refs = {front, back};
    var printStates = List.of(c.printFrontState(), c.printBackState());
    boolean unknownRead = false;
    for (int i = 0; i < 2; i++) {
      String official = text(row, "PRINT_" + refs[i].toUpperCase(Locale.ROOT));
      String result;
      if (printStates.get(i) == UNREADABLE) result = "NOT_COMPARED";
      else if (!present(official)) {
        result = "UNKNOWN";
        unknownRead |= printStates.get(i) == READABLE;
      } else if (printStates.get(i) == NONE || !imprint(observedPrints[i]).equals(imprint(official))) return null;
      else { result = "MATCH"; imprints++; }
      details.add(new PillMatchDetail(i == 0 ? "printFront" : "printBack", "print" + refs[i],
          observedPrints[i], official, result));
    }
    if (imprints == 0 || !present(text(row, "DRUG_SHAPE"))
        || !shape(c.shape()).equals(shape(text(row, "DRUG_SHAPE")))) return null;
    var actualColors = colors(text(row, "COLOR_CLASS1"), text(row, "COLOR_CLASS2"));
    var observedColors = colors(c.colorClass1(), c.colorClass2());
    if (observedColors.isEmpty() || !actualColors.containsAll(observedColors)) return null;
    details.add(new PillMatchDetail("shape", "shape", c.shape(), text(row, "DRUG_SHAPE"), "MATCH"));
    details.add(new PillMatchDetail("color", "color", observedColors.stream().sorted().toList(),
        actualColors.stream().sorted().toList(), "MATCH"));
    String[] observedLines = {c.lineFront(), c.lineBack()};
    var lineStates = List.of(c.lineFrontState(), c.lineBackState());
    for (int i = 0; i < 2; i++) {
      String official = line(text(row, "LINE_" + refs[i].toUpperCase(Locale.ROOT)));
      String value = lineStates.get(i) == NONE ? "NONE" : observedLines[i];
      String result;
      if (lineStates.get(i) == UNREADABLE) result = "NOT_COMPARED";
      else if (official == null) result = "UNKNOWN";
      else if (!official.equals(value)) return null;
      else { result = "MATCH"; lines++; }
      details.add(new PillMatchDetail(i == 0 ? "lineFront" : "lineBack", "line" + refs[i],
          value, official, result));
    }
    return new Match(row, imprints, lines, reverse ? "SWAPPED" : "DIRECT",
        unknownRead ? null : Boolean.TRUE, List.copyOf(details));
  }

  /** 각인의 구두점은 유지하고 Unicode 폭·대소문자·공백만 정규화함. */
  private static String imprint(String value) {
    return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toUpperCase(Locale.ROOT).replaceAll("(?U)\\s+", "");
  }

  private static String word(String value) {
    return imprint(value).replaceAll("[^\\p{L}\\p{N}]", "");
  }
  private static String shape(String value) {
    return switch (word(value)) {
      case "원형", "둥근형", "ROUND", "CIRCLE" -> "ROUND";
      case "타원형", "타원", "OVAL", "ELLIPSE" -> "OVAL";
      case "장방형", "직사각형", "긴타원형", "OBLONG", "RECTANGLE", "RECTANGULAR" -> "OBLONG";
      case "반원형", "반원", "SEMICIRCLE", "SEMICIRCULAR" -> "SEMICIRCLE";
      case "삼각형", "TRIANGLE", "TRIANGULAR" -> "TRIANGLE";
      case "사각형", "정사각형", "SQUARE" -> "SQUARE";
      case "마름모형", "마름모", "DIAMOND", "RHOMBUS" -> "DIAMOND";
      case "오각형", "PENTAGON", "PENTAGONAL" -> "PENTAGON";
      case "육각형", "HEXAGON", "HEXAGONAL" -> "HEXAGON";
      case "팔각형", "OCTAGON", "OCTAGONAL" -> "OCTAGON";
      case "기타", "OTHER" -> "OTHER";
      default -> word(value);
    };
  }

  private static Set<String> colors(String... values) {
    var result = new LinkedHashSet<String>();
    for (String value : values) if (present(value)) for (String part : value
        .replaceAll("(?i)light\\s+green", "LIGHTGREEN")
        .replaceAll("(?i)(?:navy|dark)\\s+blue", "NAVYBLUE").split("[,/|·\\s]+")) {
      String color = switch (word(part)) {
        case "하양", "하얀색", "흰색", "백색", "WHITE" -> "WHITE";
        case "노랑", "노란색", "황색", "YELLOW" -> "YELLOW";
        case "분홍", "분홍색", "PINK" -> "PINK";
        case "파랑", "파란색", "청색", "BLUE" -> "BLUE";
        case "주황", "주황색", "ORANGE" -> "ORANGE";
        case "빨강", "빨간색", "적색", "RED" -> "RED";
        case "초록", "초록색", "녹색", "GREEN" -> "GREEN";
        case "연두", "연두색", "LIGHTGREEN" -> "LIGHTGREEN";
        case "청록", "청록색", "TEAL", "TURQUOISE" -> "TEAL";
        case "남색", "NAVY", "NAVYBLUE", "DARKBLUE" -> "NAVY";
        case "자주", "자주색", "MAROON", "MAGENTA" -> "MAROON";
        case "투명", "TRANSPARENT", "CLEAR" -> "TRANSPARENT";
        case "갈색", "BROWN" -> "BROWN";
        case "검정", "검정색", "검은색", "흑색", "BLACK" -> "BLACK";
        case "보라", "보라색", "PURPLE" -> "PURPLE";
        case "회색", "GRAY", "GREY" -> "GRAY";
        default -> word(part);
      };
      if (!color.isEmpty()) result.add(color);
    }
    return result;
  }

  private static String line(String value) {
    if (!present(value)) return null;
    return switch (value.strip().toUpperCase(Locale.ROOT)) {
      case "-", "MINUS", "일자", "일자형" -> "MINUS";
      case "+", "PLUS", "십자", "십자형" -> "PLUS";
      case "없음", "없다", "NONE", "NO" -> "NONE";
      default -> null;
    };
  }

  public static boolean present(String value) {
    return value != null && !value.isBlank()
        && !Set.of("NULL", "UNDEFINED", "UNKNOWN", "미확인", "모름").contains(value.strip().toUpperCase(Locale.ROOT));
  }

  public static String text(JsonNode row, String field) {
    if (row == null) return null;
    for (var property : row.properties()) if (property.getKey().equalsIgnoreCase(field)) {
      String value = property.getValue().asString("").strip();
      return present(value) ? value : null;
    }
    return null;
  }
}
