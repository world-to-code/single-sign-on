package com.example.sso.metadata.internal.application;

import com.example.sso.shared.error.BadRequestException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartRequest;

/**
 * Everything that must be true of an uploaded file before a parser sees it.
 *
 * <p>Checking the extension is not validation. The filename and the content type are both chosen by the
 * client, so neither says anything about the bytes; they are kept because they catch the common case of a
 * person picking the wrong file, and refused for nothing else. The checks that carry weight look at the
 * content: a size ceiling applied before decoding, a scan for bytes that do not occur in text, and a strict
 * UTF-8 decode.
 *
 * <p>Ordering matters and is deliberate. Size first, because every later check costs memory proportional to
 * the file. Then the bytes, because a renamed binary should be refused before we try to read it as text. The
 * decode is last and strict — a lenient one turns undecodable bytes into U+FFFD, and that character then
 * lands in a username nobody typed and nobody can log in with.
 */
@Component
class CsvUploadValidator {

    /**
     * The control characters a CSV legitimately contains. Everything else below 0x20 — and 0x7F — indicates
     * the file is not text at all, which is the cheapest reliable tell that something was renamed to .csv.
     * NUL is the one that matters most: it cannot appear in text and it terminates strings in other languages.
     */
    private static final Set<Character> ALLOWED_CONTROLS = Set.of('\t', '\r', '\n');

    /** Permissive on purpose: browsers, Excel and curl disagree about what a CSV is. */
    private static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "text/csv", "text/plain", "application/csv", "application/vnd.ms-excel",
            "application/octet-stream");

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final int maxBytes;

    CsvUploadValidator(@Value("${sso.metadata.csv-import.max-file-bytes}") int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * The one file in this request, validated.
     *
     * <p>Counting the parts is its own check. Spring binds the part it was asked for and ignores the rest, so
     * a request carrying ten files is accepted as though it carried one — nine uploads nobody reviewed, each
     * of which passed the container's per-file ceiling while the request as a whole did not have to. An import
     * is a deliberate act on a file an administrator chose; there is exactly one of those.
     */
    CsvUpload validateOnly(MultipartRequest request, String partName) {
        if (request.getFileMap().size() != 1) {
            throw BadRequestException.of("metadata.csv.oneFileOnly");
        }
        MultipartFile file = request.getFile(partName);
        if (file == null) {
            throw BadRequestException.of("metadata.csv.oneFileOnly");
        }
        return validate(file);
    }

    CsvUpload validate(MultipartFile file) {
        requireCsvName(file);
        byte[] content = contentOf(file);
        if (content.length == 0) {
            throw BadRequestException.of("metadata.csv.empty");
        }
        if (content.length > maxBytes) {
            // Also capped at the container (spring.servlet.multipart.max-file-size), which is what stops the
            // bytes reaching the heap at all. This one is the honest error for a file that got past that.
            throw BadRequestException.of("metadata.csv.tooLarge");
        }
        requireText(content);
        return new CsvUpload(file.getOriginalFilename(), decode(content));
    }

    /**
     * The name and the content type, which catch a mistake and nothing more.
     *
     * <p>The extension is taken from the last segment of the last path component, so {@code users.csv.exe}
     * and {@code ../../etc/passwd.csv} both fail — the first because it is not a CSV, the second because a
     * name carrying a path is one we should never have echoed anywhere.
     */
    private void requireCsvName(MultipartFile file) {
        String name = file.getOriginalFilename();
        boolean namedCsv = name != null
                && name.toLowerCase(Locale.ROOT).endsWith(".csv")
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && !name.contains("..");
        String contentType = file.getContentType();
        boolean plausibleType = contentType == null
                || ACCEPTED_CONTENT_TYPES.contains(contentType.split(";")[0].trim().toLowerCase(Locale.ROOT));
        if (!namedCsv || !plausibleType) {
            throw BadRequestException.of("metadata.csv.notCsvFile");
        }
    }

    /** A byte that cannot occur in text means the file is not one, whatever it is named. */
    private void requireText(byte[] content) {
        for (byte b : content) {
            char c = (char) (b & 0xFF);
            if ((c < 0x20 && !ALLOWED_CONTROLS.contains(c)) || c == 0x7F) {
                throw BadRequestException.of("metadata.csv.notText");
            }
        }
    }

    /**
     * Strict UTF-8, with a leading byte-order mark removed.
     *
     * <p>REPORT rather than REPLACE: a spreadsheet saved as Latin-1 should be told it is not UTF-8, not
     * silently imported with U+FFFD where its accented characters were. The mark is stripped because it would
     * otherwise become part of the first header name, so that one column matches nothing while the rest work.
     */
    private String decode(byte[] content) {
        byte[] body = startsWithBom(content)
                ? Arrays.copyOfRange(content, UTF8_BOM.length, content.length)
                : content;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw BadRequestException.of("metadata.csv.notUtf8");
        }
    }

    private boolean startsWithBom(byte[] content) {
        if (content.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (content[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] contentOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
