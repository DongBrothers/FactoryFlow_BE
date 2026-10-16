package com.factoryflow.production.domain;

/**
 * 차대번호(VIN) 발행 규칙 (docs/specs/production-completion.md "VIN 형식"). 17자: WMI(KMH) + 제품 id 5자리 + 체크 자리
 * + 연식 코드 + 공장(U) + 작업지시 id 6자리. 작업지시 id 가 일련번호라 겹치지 않는다. I, O, Q 는 쓰지 않는다.
 */
public final class Vin {

    private static final String WMI = "KMH";
    private static final char PLANT = 'U';
    private static final String YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789";
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final String LETTER_VALUES = "ABCDEFGHJKLMNPRSTUVWXYZ";
    private static final int[] LETTER_NUMBERS = {
        1, 2, 3, 4, 5, 6, 7, 8, 1, 2, 3, 4, 5, 7, 9, 2, 3, 4, 5, 6, 7, 8, 9
    };

    private Vin() {}

    /** 제품 id(최대 5자리), 작업지시 id(최대 6자리), 완료 연도로 VIN 을 만든다. 자리를 넘으면 IllegalArgumentException. */
    public static String issue(long productId, long workOrderId, int year) {
        String body =
                WMI + digits(productId, 5) + '0' + yearCode(year) + PLANT + digits(workOrderId, 6);
        return body.substring(0, 8) + checkDigit(body) + body.substring(9);
    }

    /** 북미 VIN 체크 자리: 글자를 숫자로 바꿔 자리별 가중치를 곱한 합의 mod 11. 10 이면 X. 9번째 자리 값은 쓰지 않는다. */
    public static char checkDigit(String vin) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += valueOf(vin.charAt(i)) * WEIGHTS[i];
        }
        int remainder = sum % 11;
        return remainder == 10 ? 'X' : (char) ('0' + remainder);
    }

    private static int valueOf(char c) {
        if (Character.isDigit(c)) {
            return c - '0';
        }
        return LETTER_NUMBERS[LETTER_VALUES.indexOf(c)];
    }

    /** 연식 코드: 1980 = A 부터 30년 주기, I·O·Q·U·Z·0 은 쓰지 않는다 (2026 = T). */
    private static char yearCode(int year) {
        return YEAR_CODES.charAt(Math.floorMod(year - 1980, YEAR_CODES.length()));
    }

    private static String digits(long value, int width) {
        String text = Long.toString(value);
        if (value < 0 || text.length() > width) {
            throw new IllegalArgumentException("VIN 자리에 맞지 않는 값: " + value);
        }
        return "0".repeat(width - text.length()) + text;
    }
}
