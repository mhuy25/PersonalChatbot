package com.example.personalchatbot.service.chunk;

import com.example.personalchatbot.dto.ChunkingOptions;
import com.example.personalchatbot.config.ErrorConfig;
import com.example.personalchatbot.dto.MetadataDto;
import com.example.personalchatbot.entity.ChunkMessage;
import com.example.personalchatbot.exception.AppException;
import com.example.personalchatbot.service.implement.ChunkServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ChunkService implements ChunkServiceImpl {
    private final Encoding embeddingEncoding;
    private final ObjectMapper objectMapper;
    private final ChunkingOptions textOption = new ChunkingOptions(500, 80, 200, Locale.forLanguageTag("vi-VN"), true);
    private final ChunkingOptions javaOption = new ChunkingOptions(300, 80, 100, Locale.forLanguageTag("vi-VN"), true);


    @Override
    public List<ChunkMessage> chunkText(String docId, String text, ChunkingOptions options, MetadataDto metadataDto) {
        try {
            options = options == null ? textOption : options;
            String normalized = normalize(text);
            Map<String, Object> metadata = initMetadata(options, metadataDto);
            // (1) Trường hợp toàn bộ tài liệu quá ngắn -> emit 1 chunk duy nhất
            if (countTokens(normalized) <= options.getMinChunkTokens()) {
                return List.of(chunk(docId, 1, normalized, metadata));
            }

            List<String> units = options.getMarkdownAware()
                    ? splitMarkdownBlocks(normalized)
                    : splitParagraphs(normalized);

            List<ChunkMessage> out = new ArrayList<>();
            int chunkSeq = 1;

            List<String> carryOverlap = List.of(); // giữ phần overlap từ chunk trước

            List<String> pendingSmall = new ArrayList<>();
            int pendingTokens = 0;

            for (String unit : units) {
                List<String> sentences = splitSentences(unit, options.getLocale());

                List<String> buf = new ArrayList<>(carryOverlap); // bắt đầu với overlap trước đó

                if (!pendingSmall.isEmpty()) {
                    buf.addAll(0, pendingSmall);
                    pendingSmall.clear();
                    pendingTokens = 0;
                }

                int tokens = countTokens(join(buf));
                for (String s : sentences) {
                    int t = countTokens(s);
                    if (tokens + t > options.getMaxTokens() && !buf.isEmpty()) {
                        // emit chunk hiện tại
                        String content = join(buf);
                        int countedTokens = countTokens(content);
                        if (countedTokens >= options.getMinChunkTokens()) {
                            if (pendingTokens > 0 && countedTokens + pendingTokens <= options.getMaxTokens()) {
                                content = join(pendingSmall) + "\n\n" + content;
                                pendingSmall.clear();
                                pendingTokens = 0;
                            }
                            out.add(chunk(docId, chunkSeq++, content, metadata));
                        }
                        else {
                            pendingSmall.add(content);
                            pendingTokens += countedTokens;
                        }
                        // tính overlap cho chunk kế
                        carryOverlap = makeSentenceOverlap(buf, options.getOverlapTokens());
                        buf = new ArrayList<>(carryOverlap);
                        tokens = countTokens(join(buf));
                    }
                    buf.add(s);
                    tokens += t;
                }
                if (!buf.isEmpty()) {
                    String content = join(buf);
                    int countedTokens = countTokens(content);
                    if (countedTokens >= options.getMinChunkTokens()) {
                        if (pendingTokens > 0 &&
                                countedTokens + pendingTokens <= options.getMaxTokens()) {
                            content = join(pendingSmall) + "\n\n" + content;
                            pendingSmall.clear();
                            pendingTokens = 0;
                        }
                        out.add(chunk(docId, chunkSeq++, content, metadata));
                        carryOverlap = makeSentenceOverlap(buf, options.getOverlapTokens());
                    }
                    else {
                        pendingSmall.add(content);
                        pendingTokens += countedTokens;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,e.getMessage());
        }
    }

    @Override
    public List<ChunkMessage> chunkJavaSource(String docId, String javaSource, MetadataDto metadataDto) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaSource);
            List<ChunkMessage> out = new ArrayList<>();
            AtomicInteger seq = new AtomicInteger(1);

            // Ưu tiên: lớp/enum + từng method/constructor
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String header = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
                // 1) JavaDoc/field/annotations của class: coi như block đầu
                String classHeaderBlock = cls.toString().lines()
                        .takeWhile(l -> !l.stripLeading().startsWith("{")) // phần trước body
                        .reduce((a,b)->a+"\n"+b).orElse("");

                out.addAll(chunkText(docId,
                        "Class " + header + "\n" + classHeaderBlock, javaOption, metadataDto).stream()
                        .peek(c -> c.setChunkId((long) seq.getAndIncrement()))
                        .toList());

                // 2) Từng method/constructor là một block lớn → pack theo token
                List<BodyDeclaration<?>> members = new ArrayList<>();
                members.addAll(cls.getMethods());
                members.addAll(cls.getConstructors());

                for (BodyDeclaration<?> m : members) {
                    String sig = (m instanceof MethodDeclaration md)
                            ? md.getDeclarationAsString(false, false, true)
                            : (m instanceof ConstructorDeclaration cd ? cd.getDeclarationAsString(false, false, true) : m.getMetaModel().toString());

                    String block = "// " + header + " :: " + sig + "\n" + m;
                    for (ChunkMessage cm : chunkText(docId, block, javaOption, metadataDto)) {
                        cm.setChunkId((long) seq.getAndIncrement());
                        out.add(cm);
                    }
                }
            });

            // Nếu file không có class (hiếm), chunk toàn bộ như text
            if (out.isEmpty()) {
                List<ChunkMessage> solo = chunkText(docId, javaSource, javaOption, metadataDto);
                assignIds(solo, seq);
                out.addAll(solo);
            }
            return out;
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,e.getMessage());
        }
    }

    private void assignIds(List<ChunkMessage> list, AtomicInteger seq) {
        for (ChunkMessage c : list) c.setChunkId((long) seq.getAndIncrement());
    }

    private ChunkMessage chunk(String docId, int seq, String content, Map<String, Object> metadata) {
        var cm = new ChunkMessage();
        try {
            cm.setDocId(docId);
            cm.setChunkId((long) seq);
            cm.setContent(content);
            cm.setMetadata(metadata == null ? null : objectMapper.writeValueAsString(metadata)); // jsonb
            cm.setUpdatedAt(java.time.LocalDateTime.now());
        } catch (Exception e) {
            // fallback an toàn nếu JSON lỗi
            cm.setMetadata(null);
        }
        return cm;
    }

    private String normalize(String s) {
        // Gom dòng trống, chuẩn newline, trim
        String x = s.replace("\r\n", "\n").replace("\r", "\n");
        x = Pattern.compile("\n{3,}").matcher(x).replaceAll("\n\n");
        return x.trim();
    }

    private List<String> splitMarkdownBlocks(String text) {
        // Cắt theo heading hoặc đoạn trống
        List<String> blocks = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder cur = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("#") || line.matches("(?i)^\\s*[-*]\\s+.*$")) { // heading hoặc bullet
                if (!cur.isEmpty()) { blocks.add(cur.toString().trim()); cur.setLength(0); }
            }
            cur.append(line).append('\n');
        }
        if (!cur.isEmpty()) blocks.add(cur.toString().trim());
        return blocks.isEmpty() ? List.of(text) : blocks;
    }

    private List<String> splitParagraphs(String text) {
        return Arrays.stream(text.split("\\n\\s*\\n")).map(String::trim).filter(s->!s.isEmpty()).toList();
    }

    private List<String> splitSentences(String text, Locale locale) {
        // Dùng BreakIterator (ổn), có thể nâng cấp sang ICU4J nếu cần độ chính xác cao hơn
        BreakIterator bi = BreakIterator.getSentenceInstance(locale);
        bi.setText(text);
        List<String> result = new ArrayList<>();
        int start = bi.first(), end = bi.next();
        while (end != BreakIterator.DONE) {
            String s = text.substring(start, end).trim();
            if (!s.isEmpty()) result.add(s);
            start = end;
            end = bi.next();
        }
        if (result.isEmpty()) result = List.of(text);
        return result;
    }

    private int countTokens(String s) {
        return embeddingEncoding.countTokens(s);
    }

    private String join(List<String> parts) {
        return String.join("\n", parts);
    }

    private List<String> makeSentenceOverlap(List<String> buf, int overlapTokens) {
        // Lấy từ cuối buffer lên đến khi đạt >= overlapTokens
        List<String> result = new ArrayList<>();
        int tokens = 0;
        ListIterator<String> it = buf.listIterator(buf.size());
        while (it.hasPrevious() && tokens < overlapTokens) {
            String prev = it.previous();
            result.add(prev);
            tokens += countTokens(prev);
        }
        Collections.reverse(result);
        return result;
    }

    private Map<String, Object> initMetadata(ChunkingOptions options, MetadataDto metadataDto) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", options.getMarkdownAware() ? "markdown" : "text");
        metadata.put("locale", options.getLocale().toLanguageTag());
        metadata.put("chunking", Map.of(
                "maxTokens", options.getMaxTokens(),
                "overlapTokens", options.getOverlapTokens(),
                "minChunkTokens", options.getMinChunkTokens()
        ));
        metadata.put("tokenizer", EncodingType.CL100K_BASE); // hoặc bạn đưa từ config nếu khác
        if (metadataDto != null) {
            metadata.put("project", metadataDto.getProject());
            metadata.put("module",  metadataDto.getModule());
            metadata.put("env",     metadataDto.getEnv());
            metadata.put("title",   metadataDto.getTitle());
            metadata.put("path",    metadataDto.getPath());
            metadata.putAll(metadataDto.getExtra());
        }
        return metadata;
    }
}
