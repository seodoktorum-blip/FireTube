package com.uykutube.app;

/* Ham HTML baytlarında "<head ...>" etiketinin kapanış '>' konumu. Bayt düzeyinde
   aranır: belge parça parça okunurken parçayı UTF-8 dizgiye çevirmek, sınırda
   bölünen çok baytlı harfi bozardı (sonra geri kodlanınca U+FFFD olurdu). */
final class HtmlHead {
    private static final byte[] TAG = {'<', 'h', 'e', 'a', 'd'};

    private HtmlHead() {
    }

    static int end(byte[] b, int len) {
        outer:
        for (int i = 0; i + TAG.length <= len; i++) {
            for (int k = 0; k < TAG.length; k++) {
                if (b[i + k] != TAG[k]) continue outer;
            }
            // "<header" değil, "<head>" ya da "<head ...>"
            int after = i + TAG.length;
            if (after < len && b[after] != '>' && b[after] != ' ' && b[after] != '\t'
                    && b[after] != '\n' && b[after] != '\r') continue;
            for (int j = after; j < len; j++) {
                if (b[j] == '>') return j;
            }
            return -1; // etiket henüz tamamlanmadı: daha fazla okunmalı
        }
        return -1;
    }
}
