package db.migration;

import com.ihrms.domain.support.CompanySlug;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V28 — the company slug foundation (ARCHITECTURE.md §4). Adds {@code companies.slug}, backfills every
 * existing company from its name (deduped + reserved-safe via {@link CompanySlug} — the SAME algorithm
 * the create path uses), then enforces NOT NULL + a case-insensitive unique index. A Java migration
 * (not SQL) so the one slug algorithm is shared rather than reimplemented in SQL; deterministic
 * (oldest company first) so a re-run would produce identical slugs.
 */
public class V28__company_slug extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();

    try (Statement ddl = connection.createStatement()) {
      ddl.execute("ALTER TABLE \"companies\" ADD COLUMN \"slug\" VARCHAR(60)");
    }

    // Backfill oldest-first, deduping (case-insensitively) against slugs already assigned in this run.
    Set<String> taken = new HashSet<>();
    try (Statement select = connection.createStatement();
        ResultSet rows =
            select.executeQuery(
                "SELECT \"id\", \"name\" FROM \"companies\" ORDER BY \"createdAt\" ASC, \"id\" ASC");
        PreparedStatement update =
            connection.prepareStatement("UPDATE \"companies\" SET \"slug\" = ? WHERE \"id\" = ?")) {
      while (rows.next()) {
        String slug = CompanySlug.generate(rows.getString("name"), taken, CompanySlug.RESERVED);
        taken.add(slug.toLowerCase());
        update.setString(1, slug);
        update.setString(2, rows.getString("id"));
        update.executeUpdate();
      }
    }

    try (Statement ddl = connection.createStatement()) {
      ddl.execute("ALTER TABLE \"companies\" ALTER COLUMN \"slug\" SET NOT NULL");
      ddl.execute("CREATE UNIQUE INDEX \"companies_slug_key\" ON \"companies\" (lower(\"slug\"))");
    }
  }
}
