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
    Boolean cancelled,
    String orientation,
    Integer evidenceRank,
    List<PillMatchDetail> matchDetails) {
  public PhotoCandidateData(String itemSeq, String name, String maker, String etcOtc,
      String className, String imageUrl, String printFront, String printBack, String shape,
      String color, String lineFront, String lineBack, String form, String material,
      Boolean printMatched, Boolean cancelled) {
    this(itemSeq, name, maker, etcOtc, className, imageUrl, printFront, printBack, shape,
        color, lineFront, lineBack, form, material, printMatched, cancelled, null, null, null);
  }
  public PhotoCandidateData withMatch(String orientation, int rank, List<PillMatchDetail> details) {
    return new PhotoCandidateData(itemSeq, name, maker, etcOtc, className, imageUrl,
        printFront, printBack, shape, color, lineFront, lineBack, form, material,
        printMatched, cancelled, orientation, rank, details);
  }
  public record Group(
      int readNo, String readName, String lookup, List<PhotoCandidateData> candidates) {}
}
