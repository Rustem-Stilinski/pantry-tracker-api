package com.usedlog.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Barcode numbers: check digits and the one canonical form (examples are the standard published ones). */
class BarcodesTests {

    @Test
    void upcAGetsALeadingZeroSoItMatchesTheSameCodeReadAsEan13() {
        assertThat(Barcodes.normalize("036000291452", "upc_a")).isEqualTo("0036000291452");
        assertThat(Barcodes.normalize("0036000291452", "ean_13")).isEqualTo("0036000291452");
        assertThat(Barcodes.normalize(" 0 36000-29145 2 ", null)).isEqualTo("0036000291452");
    }

    @Test
    void ean13AndEan8KeepTheirDigits() {
        assertThat(Barcodes.normalize("4006381333931", null)).isEqualTo("4006381333931");
        assertThat(Barcodes.normalize("96385074", "ean_8")).isEqualTo("96385074");
        assertThat(Barcodes.normalize("96385074", null)).isEqualTo("96385074");
    }

    @Test
    void upcEExpandsToTheFullCode() {
        // UPC-E 04252614 is UPC-A 042100005264.
        assertThat(Barcodes.normalize("04252614", "upc_e")).isEqualTo("0042100005264");
        // Typed without a format: not a valid EAN-8, so it's read as UPC-E.
        assertThat(Barcodes.normalize("04252614", null)).isEqualTo("0042100005264");
        assertThat(Barcodes.upcEToEan13("04252613")).isNull();
        assertThat(Barcodes.upcEToEan13("24252614")).isNull();
    }

    @Test
    void gtin14WithAZeroIndicatorIsTheEan13() {
        assertThat(Barcodes.normalize("04006381333931", null)).isEqualTo("4006381333931");
        assertThat(Barcodes.normalize("10012345678902", null)).isEqualTo("10012345678902");
    }

    @Test
    void misreadsAndOtherTextAreRejected() {
        assertThat(Barcodes.normalize("036000291453", null)).isNull();   // wrong check digit
        assertThat(Barcodes.normalize("4006381333932", null)).isNull();
        assertThat(Barcodes.normalize("96385075", "ean_8")).isNull();
        assertThat(Barcodes.normalize("12345", null)).isNull();
        assertThat(Barcodes.normalize("hello world", null)).isNull();
        assertThat(Barcodes.normalize("", null)).isNull();
        assertThat(Barcodes.normalize(null, null)).isNull();
    }
}
