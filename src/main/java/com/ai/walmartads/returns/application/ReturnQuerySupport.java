package com.ai.walmartads.returns.application;

/**
 * Shared active-return source. A return report can repeat the same business
 * record in a later import with an updated status, so every consumer must use
 * only the newest active version.
 */
public final class ReturnQuerySupport {

    private ReturnQuerySupport() {
    }

    public static final String ACTIVE_RETURN_CTE = """
            with ranked_returns as (
                select returns.*,
                       row_number() over (
                           partition by returns.business_key
                           order by file.created_at desc, returns.id desc
                       ) as version_rank
                  from wm_ads_return_order returns
                  join wm_ads_import_file file
                    on file.id = returns.import_file_id
                   and file.active = true
            ),
            active_return_rows as (
                select returns.*
                  from ranked_returns returns
                 where returns.version_rank = 1
            )
            """;
}
