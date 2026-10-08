package com.ai.walmartads.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AiSkillService {

    private final JdbcTemplate jdbcTemplate;

    public AiSkillService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SkillDefinition> enabledSkills() {
        return jdbcTemplate.query("""
                select skill_code, skill_name, description, instructions, output_schema, version
                  from wm_ads_ai_skill
                 where enabled = 1
                 order by skill_name, skill_code
                """, (rs, rowNum) -> new SkillDefinition(
                rs.getString("skill_code"),
                rs.getString("skill_name"),
                rs.getString("description"),
                rs.getString("instructions"),
                rs.getString("output_schema"),
                rs.getInt("version"),
                tools(rs.getString("skill_code"))
        ));
    }

    public Optional<SkillDefinition> enabledSkill(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        List<SkillDefinition> matches = jdbcTemplate.query("""
                select skill_code, skill_name, description, instructions, output_schema, version
                  from wm_ads_ai_skill
                 where enabled = 1 and skill_code = ?
                """, (rs, rowNum) -> new SkillDefinition(
                rs.getString("skill_code"),
                rs.getString("skill_name"),
                rs.getString("description"),
                rs.getString("instructions"),
                rs.getString("output_schema"),
                rs.getInt("version"),
                tools(rs.getString("skill_code"))
        ), code);
        return matches.stream().findFirst();
    }

    private List<String> tools(String skillCode) {
        return jdbcTemplate.queryForList("""
                select tool_code
                  from wm_ads_ai_skill_tool
                 where skill_code = ? and enabled = 1
                 order by sort_order, tool_code
                """, String.class, skillCode);
    }

    public record SkillDefinition(
            String code,
            String name,
            String description,
            String instructions,
            String outputSchema,
            int version,
            List<String> tools
    ) {
    }
}
