package org.semiproject.pillbadeumserver.image.domain;

import java.util.List;

/** 공식 응답에서만 만든 저장용 값이다. AI가 만든 품목코드를 받지 않는다. */
public record PhotoCandidateData(
    String itemSeq,
    String name,
    String maker,
    String etcOtc,
    String className,
    String imageUrl,
    String printFront,
    String printBack,
    String shape,
    String color,
    String lineFront,
    String lineBack,
    String form,
    String material,
    Boolean printMatched,
    Boolean cancelled) {
  public record Group(
      int readNo, String readName, String lookup, List<PhotoCandidateData> candidates) {}
}
