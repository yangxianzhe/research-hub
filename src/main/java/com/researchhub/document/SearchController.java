package com.researchhub.document;

import com.researchhub.auth.AuthInterceptor;
import com.researchhub.common.ApiException;
import com.researchhub.workspace.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/search")
public class SearchController {
    public record Hit(String documentId, String title, int pageNumber, int chunkIndex, String excerpt, int score) { }
    private static final Pattern WORD = Pattern.compile("[a-z0-9_]{2,}|[\\p{IsHan}]+");
    private final JdbcTemplate db;
    private final AccessService access;

    public SearchController(JdbcTemplate db, AccessService access) { this.db = db; this.access = access; }

    @GetMapping
    public List<Hit> search(@PathVariable String workspaceId, @RequestParam String q, HttpServletRequest request) {
        String userId = AuthInterceptor.userId(request);
        access.requireMember(workspaceId, userId);
        if (q.isBlank() || q.length() > 200) throw ApiException.badRequest("查询长度应为 1 到 200 字符");
        var terms = terms(q);
        if (terms.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>(List.of(workspaceId, userId));
        List<String> conditions = new ArrayList<>();
        for (String term : terms) {
            conditions.add("(LOWER(c.content) LIKE ? ESCAPE '!' OR LOWER(d.title) LIKE ? ESCAPE '!')");
            String pattern = "%" + term.replace("!", "!!").replace("_", "!_") + "%";
            args.add(pattern);
            args.add(pattern);
        }
        String sql = """
                SELECT d.id,d.title,c.page_number,c.chunk_index,c.content FROM documents d
                JOIN document_chunks c ON c.document_id=d.id WHERE d.workspace_id=? AND d.status='READY'
                AND (d.visibility='GROUP' OR d.owner_id=?) AND (
                """ + String.join(" OR ", conditions) + ") ORDER BY d.created_at DESC,c.chunk_index LIMIT 5000";
        return db.query(sql, (rs, n) -> {
            String title = rs.getString(2);
            String content = rs.getString(5);
            int score = 0;
            for (String term : terms) {
                if (title.toLowerCase(Locale.ROOT).contains(term)) score += 3;
                if (content.toLowerCase(Locale.ROOT).contains(term)) score++;
            }
            return new Hit(rs.getString(1), title, rs.getInt(3), rs.getInt(4), content, score);
        }, args.toArray()).stream().sorted(Comparator.comparingInt(Hit::score).reversed()).limit(20).toList();
    }

    private static List<String> terms(String query) {
        var terms = new LinkedHashSet<String>();
        var matcher = WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < 16) {
            String word = matcher.group();
            if (word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                if (points.length == 1) terms.add(word);
                for (int i = 0; i + 1 < points.length && terms.size() < 16; i++) terms.add(new String(points, i, 2));
            } else terms.add(word);
        }
        return List.copyOf(terms);
    }
}
