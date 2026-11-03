package org.semiproject.pillbadeumserver.chat.service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * V3-KAKAO-006(8.3): 받은 음성의 형식과 재생 시간을 파일 머리에서 읽는다. 파일 이름·요청의 형식 표시는 믿지 않는다.
 * m4a(MP4 컨테이너의 moov/mvhd)와 WAV(fmt·data 묶음)만 읽고, 모양이 틀리거나 재생 시간이 없으면 빈 값을 돌려준다.
 */
public record AudioClip(Format format, long durationMillis) {
    private static final int MAX_BOXES = 64;   // 상자·묶음을 이보다 많이 넘기지 않음(망가진 파일에서 오래 돌지 않게)

    public enum Format {
        M4A("voice.m4a", "audio/mp4"), WAV("voice.wav", "audio/wav");

        private final String fileName;
        private final String mediaType;

        Format(String fileName, String mediaType) {
            this.fileName = fileName;
            this.mediaType = mediaType;
        }

        public String fileName() { return fileName; }
        public String mediaType() { return mediaType; }
    }

    public static Optional<AudioClip> read(byte[] bytes) {
        if (bytes.length >= 12 && ascii(bytes, 4, "ftyp"))
            return mp4Millis(bytes).map(millis -> new AudioClip(Format.M4A, millis));
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE"))
            return wavMillis(bytes).map(millis -> new AudioClip(Format.WAV, millis));
        return Optional.empty();
    }

    /** moov 상자 안 mvhd의 timescale·duration. moov가 mdat 뒤에 있어도 상자 크기로 건너뛰어 찾는다. */
    private static Optional<Long> mp4Millis(byte[] b) {
        int[] moov = child(b, 0, b.length, "moov");
        int[] mvhd = moov == null ? null : child(b, moov[0], moov[1], "mvhd");
        if (mvhd == null || mvhd[0] + 4 > mvhd[1]) return Optional.empty();
        int at = mvhd[0];
        long timescale, duration;
        if ((b[at] & 0xff) == 0 && at + 20 <= mvhd[1]) {
            timescale = u32(b, at + 12);
            duration = u32(b, at + 16);
            if (duration == 0xFFFFFFFFL) return Optional.empty();
        } else if ((b[at] & 0xff) == 1 && at + 32 <= mvhd[1]) {
            timescale = u32(b, at + 20);
            duration = u64(b, at + 24);
            if (duration < 0) return Optional.empty();   // 모름(전부 1)이거나 쓸 수 없는 값
        } else {
            return Optional.empty();
        }
        if (timescale == 0) return Optional.empty();
        return Optional.of(duration > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : duration * 1000 / timescale);
    }

    /** [from, to) 안에서 이름이 type인 첫 상자의 [내용 시작, 끝)을 찾는다. 크기가 틀린 상자를 만나면 멈춘다. */
    private static int[] child(byte[] b, int from, int to, String type) {
        int at = from;
        for (int boxes = 0; boxes < MAX_BOXES && at + 8 <= to; boxes++) {
            long size = u32(b, at);
            int header = 8;
            if (size == 1) {
                if (at + 16 > to) return null;
                size = u64(b, at + 8);
                header = 16;
            } else if (size == 0) {
                size = to - at;   // 파일 끝까지 이어지는 상자
            }
            if (size < header || size > to - at) return null;
            if (ascii(b, at + 4, type)) return new int[]{at + header, (int) (at + size)};
            at += (int) size;
        }
        return null;
    }

    /** fmt 묶음의 초당 바이트 수와 data 묶음 크기. data가 파일보다 크다고 적혀 있으면 실제로 있는 만큼만 센다. */
    private static Optional<Long> wavMillis(byte[] b) {
        long byteRate = 0;
        int at = 12;
        for (int chunks = 0; chunks < MAX_BOXES && at + 8 <= b.length; chunks++) {
            long size = u32le(b, at + 4);
            int body = at + 8;
            if (ascii(b, at, "fmt ")) {
                if (size < 16 || body + 16 > b.length) return Optional.empty();
                byteRate = u32le(b, body + 8);
            } else if (ascii(b, at, "data")) {
                if (byteRate == 0) return Optional.empty();   // fmt가 data보다 앞에 있어야 함
                return Optional.of(Math.min(size, b.length - body) * 1000 / byteRate);
            }
            long next = body + size + (size & 1);
            if (next > b.length) return Optional.empty();
            at = (int) next;
        }
        return Optional.empty();
    }

    private static boolean ascii(byte[] b, int at, String text) {
        return at + 4 <= b.length && new String(b, at, 4, StandardCharsets.US_ASCII).equals(text);
    }

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xffL) << 24) | ((b[at + 1] & 0xffL) << 16) | ((b[at + 2] & 0xffL) << 8) | (b[at + 3] & 0xffL);
    }

    private static long u64(byte[] b, int at) {
        return (u32(b, at) << 32) | u32(b, at + 4);
    }

    private static long u32le(byte[] b, int at) {
        return (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8) | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
    }
}
