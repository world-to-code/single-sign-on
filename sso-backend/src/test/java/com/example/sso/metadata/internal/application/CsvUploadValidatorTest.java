package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.BadRequestException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * What has to be true of a file before anything reads it as CSV.
 *
 * <p>An extension check is not validation — the client picks the extension and the content type both, so
 * neither says anything about the bytes. These are the checks that look at what was actually uploaded, and
 * they run BEFORE the parser: a parser handed a renamed binary or a 200MB file is already the problem.
 *
 * <p>Each case asserts the message key, not just the exception type. Six rejections that all throw
 * {@code BadRequestException} are indistinguishable otherwise, so a guard could stop working and its test
 * would keep passing on a different guard's refusal.
 */
class CsvUploadValidatorTest {

    private static final int MAX_BYTES = 1024;

    private CsvUploadValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CsvUploadValidator(new CsvImportLimits(MAX_BYTES, 100, 20, 255));
    }

    private MultipartFile upload(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private MultipartFile csv(String content) {
        return upload("users.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private void refuses(MultipartFile file, String messageKey) {
        assertThatThrownBy(() -> validator.validate(file))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey)
                .isEqualTo(messageKey);
    }

    @Test
    void anOrdinaryCsvIsAccepted() {
        String content = "username,email\nada,ada@example.com\n";

        assertThat(validator.validate(csv(content)).text()).isEqualTo(content);
    }

    @Test
    void anEmptyFileIsRefused() {
        refuses(csv(""), "metadata.csv.empty");
    }

    @Test
    void aFileOverTheCeilingIsRefused() {
        refuses(csv("a".repeat(MAX_BYTES + 1)), "metadata.csv.tooLarge");
    }

    /** The boundary belongs to the accepted side, or the limit an administrator is told is off by one. */
    @Test
    void aFileExactlyAtTheCeilingIsAccepted() {
        assertThatCode(() -> validator.validate(csv("a".repeat(MAX_BYTES)))).doesNotThrowAnyException();
    }

    // --- the bytes, not the label ------------------------------------------------------------------

    /**
     * A NUL byte does not occur in text. It is the cheapest reliable tell that something renamed a binary to
     * .csv — and the parser would otherwise carry it into a value and on into the database.
     */
    @Test
    void aFileCarryingNulBytesIsRefused() {
        refuses(upload("users.csv", "text/csv", new byte[] {'a', ',', 'b', 0, 'c'}), "metadata.csv.notText");
    }

    @Test
    void aZipRenamedToCsvIsRefused() {
        // PK\003\004 — the local file header every zip (and every xlsx, docx, jar) opens with.
        refuses(upload("users.csv", "text/csv", new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00}),
                "metadata.csv.notText");
    }

    @Test
    void aGzipRenamedToCsvIsRefused() {
        refuses(upload("users.csv", "text/csv", new byte[] {(byte) 0x1F, (byte) 0x8B, 0x08, 0x00}),
                "metadata.csv.notText");
    }

    /** Tab, CR and LF are control characters and entirely ordinary in a CSV — rejecting them rejects reality. */
    @Test
    void theControlCharactersThatBelongInACsvAreAccepted() {
        assertThatCode(() -> validator.validate(csv("a\tb,c\r\nd,e\n"))).doesNotThrowAnyException();
    }

    /**
     * Bytes that are not valid UTF-8 are refused rather than replaced. Lenient decoding turns them into U+FFFD,
     * which then lands in a username or an email — a value nobody typed and nobody can log in with.
     */
    @Test
    void undecodableBytesAreRefusedRatherThanReplaced() {
        refuses(upload("users.csv", "text/csv", new byte[] {'a', ',', (byte) 0xC3, 0x28}),
                "metadata.csv.notUtf8");
    }

    /**
     * Spreadsheet exports lead with a byte-order mark. Left in place it becomes part of the FIRST header name,
     * so that one column silently matches nothing while every other column works.
     */
    @Test
    void aByteOrderMarkIsStrippedSoTheFirstHeaderStillMatches() {
        byte[] withBom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'u', 's', 'e', 'r'};

        assertThat(validator.validate(upload("users.csv", "text/csv", withBom)).text()).isEqualTo("user");
    }

    // --- the label, which still catches mistakes ---------------------------------------------------

    @Test
    void aFilenameThatIsNotCsvIsRefused() {
        refuses(upload("users.xlsx", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)),
                "metadata.csv.notCsvFile");
    }

    @Test
    void aFilenameIsRequired() {
        refuses(upload("", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)), "metadata.csv.notCsvFile");
    }

    /**
     * The extension test must look at the filename's LAST segment. A name like {@code users.csv.exe} passes a
     * naive {@code contains(".csv")}, and a path like {@code ../../users.csv} is a name we should never echo
     * back into a header or a log line.
     */
    @Test
    void aDoubleExtensionOrAPathIsNotACsvName() {
        refuses(upload("users.csv.exe", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)),
                "metadata.csv.notCsvFile");
        refuses(upload("../../etc/passwd.csv", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)),
                "metadata.csv.notCsvFile");
    }

    /**
     * The content type is the client's word and proves nothing, so it is checked permissively — browsers and
     * Excel disagree about what a CSV is. It is here to catch a wrong file, not an attacker.
     */
    @Test
    void theContentTypesRealClientsSendAreAllAccepted() {
        for (String type : new String[] {"text/csv", "text/plain", "application/csv",
                "application/vnd.ms-excel", "application/octet-stream", null}) {
            assertThatCode(() -> validator.validate(
                    upload("users.csv", type, "a,b\n".getBytes(StandardCharsets.UTF_8))))
                    .as("content type %s", type)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void aContentTypeThatIsNotTextAtAllIsRefused() {
        refuses(upload("users.csv", "image/png", "a,b\n".getBytes(StandardCharsets.UTF_8)),
                "metadata.csv.notCsvFile");
    }

    @Test
    void everyRefusalIsABadRequest() {
        assertThatThrownBy(() -> validator.validate(csv(""))).isInstanceOf(BadRequestException.class);
    }
    // --- how many files, not just which one ---------------------------------------------------------

    private MultipartRequest requestWith(MultipartFile... files) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        for (MultipartFile file : files) {
            request.addFile(file);
        }
        return request;
    }

    @Test
    void exactlyOneFileIsAccepted() {
        assertThat(validator.validateOnly(requestWith(csv("a,b\n")), "file").text()).isEqualTo("a,b\n");
    }

    /**
     * Spring binds the part it was asked for and ignores the rest, so ten files arrive as one — nine uploads
     * nobody reviewed, each under the container's PER-FILE ceiling while the request as a whole never had to be.
     */
    @Test
    void aRequestCarryingSeveralFilesIsRefused() {
        MultipartRequest request = requestWith(csv("a,b\n"),
                new MockMultipartFile("other", "more.csv", "text/csv", "c,d\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> validator.validateOnly(request, "file"))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey)
                .isEqualTo("metadata.csv.oneFileOnly");
    }

    @Test
    void aRequestCarryingNoFileIsRefused() {
        assertThatThrownBy(() -> validator.validateOnly(requestWith(), "file"))
                .asInstanceOf(type(ApiException.class))
                .extracting(ApiException::getMessageKey)
                .isEqualTo("metadata.csv.oneFileOnly");
    }
}
