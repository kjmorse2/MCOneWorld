// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.index.SeasonFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * C2: SQLite through sqlite-jdbc, in WAL mode with {@code synchronous=FULL} so a commit is durable
 * when it returns, and readers get snapshot isolation from their own read transactions.
 *
 * <p>Schema (keys are {@link ChunkOrder} keys, so primary-key order is scan order):
 *
 * <pre>
 *   dimension(id PK, key UNIQUE)          season(id PK, msb, lsb, UNIQUE(msb, lsb))
 *   chunk(dim, k, x, z, legacy, first_season, first_s, first_n, first_ev, protected,
 *         PRIMARY KEY(dim, k)) WITHOUT ROWID;   INDEX chunk_first(first_season, dim, k)
 *   activity(dim, k, season, at_s, at_n, ev, PRIMARY KEY(dim, k, season)) WITHOUT ROWID;
 *         INDEX activity_season(season, dim, k)
 * </pre>
 */
public final class SqliteIndex extends SpikeIndexBase {
  private static final String SELECT =
      "SELECT c.k, c.legacy, c.first_season, c.first_s, c.first_n, c.first_ev, c.protected,"
          + " a.season, a.at_s, a.at_n, a.ev FROM chunk c"
          + " LEFT JOIN activity a ON a.dim = c.dim AND a.k = c.k";

  private final String url;
  private final Connection writer;
  private final Map<DimensionKey, Integer> dimensionIds = new HashMap<>();
  private final Map<Integer, DimensionKey> dimensionsById = new HashMap<>();
  private final Map<SeasonId, Integer> seasonIds = new HashMap<>();
  private final Map<Integer, SeasonId> seasonsById = new HashMap<>();

  private SqliteIndex(Path file) throws ChunkIndexException {
    this.url = "jdbc:sqlite:" + file;
    try {
      writer = DriverManager.getConnection(url);
      try (Statement s = writer.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        s.execute("PRAGMA synchronous=FULL");
        s.execute("PRAGMA cache_size=-65536");
        s.execute(
            "CREATE TABLE IF NOT EXISTS dimension(id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS season(id INTEGER PRIMARY KEY, msb INTEGER NOT NULL,"
                + " lsb INTEGER NOT NULL, UNIQUE(msb, lsb))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS chunk(dim INTEGER NOT NULL, k INTEGER NOT NULL,"
                + " x INTEGER NOT NULL, z INTEGER NOT NULL, legacy INTEGER NOT NULL,"
                + " first_season INTEGER, first_s INTEGER NOT NULL, first_n INTEGER NOT NULL,"
                + " first_ev INTEGER NOT NULL, protected INTEGER NOT NULL,"
                + " PRIMARY KEY(dim, k)) WITHOUT ROWID");
        s.execute("CREATE INDEX IF NOT EXISTS chunk_first ON chunk(first_season, dim, k)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS activity(dim INTEGER NOT NULL, k INTEGER NOT NULL,"
                + " season INTEGER NOT NULL, at_s INTEGER NOT NULL, at_n INTEGER NOT NULL,"
                + " ev INTEGER NOT NULL, PRIMARY KEY(dim, k, season)) WITHOUT ROWID");
        s.execute("CREATE INDEX IF NOT EXISTS activity_season ON activity(season, dim, k)");
        try (ResultSet rs = s.executeQuery("SELECT id, key FROM dimension")) {
          while (rs.next()) {
            DimensionKey key = DimensionKey.parse(rs.getString(2));
            dimensionIds.put(key, rs.getInt(1));
            dimensionsById.put(rs.getInt(1), key);
          }
        }
        try (ResultSet rs = s.executeQuery("SELECT id, msb, lsb FROM season")) {
          while (rs.next()) {
            SeasonId id = SeasonId.of(new UUID(rs.getLong(2), rs.getLong(3)));
            seasonIds.put(id, rs.getInt(1));
            seasonsById.put(rs.getInt(1), id);
          }
        }
      }
      writer.setAutoCommit(false);
    } catch (SQLException | RuntimeException e) {
      throw SpikeCodes.io("cannot open " + file, e);
    }
  }

  /** Opens (or creates) a C2 index in {@code directory}. */
  public static SqliteIndex open(Path directory) throws ChunkIndexException {
    try {
      Files.createDirectories(directory);
    } catch (java.io.IOException e) {
      throw SpikeCodes.io("cannot create " + directory, e);
    }
    return new SqliteIndex(directory.resolve("index.sqlite"));
  }

  // ---- reading -------------------------------------------------------------------------------

  @Override
  public ChunkIndexReader reader() throws ChunkIndexException {
    requireOpen();
    try {
      Connection connection = DriverManager.getConnection(url);
      connection.setAutoCommit(false);
      try (Statement s = connection.createStatement();
          ResultSet rs = s.executeQuery("SELECT count(*) FROM dimension")) {
        rs.next(); // the first read pins this connection's WAL snapshot
      }
      return new Reader(connection);
    } catch (SQLException e) {
      throw SpikeCodes.io("cannot open a reader", e);
    }
  }

  private final class Reader extends ReaderBase {
    private final Connection connection;

    Reader(Connection connection) {
      this.connection = connection;
    }

    @Override
    public SortedSet<DimensionKey> dimensions() throws ChunkIndexException {
      requireUsable();
      SortedSet<DimensionKey> out = new TreeSet<>();
      try (Statement s = connection.createStatement();
          ResultSet rs =
              s.executeQuery(
                  "SELECT d.key FROM dimension d"
                      + " WHERE EXISTS (SELECT 1 FROM chunk c WHERE c.dim = d.id)")) {
        while (rs.next()) {
          out.add(DimensionKey.parse(rs.getString(1)));
        }
      } catch (SQLException e) {
        throw failure(e);
      }
      return out;
    }

    @Override
    public Optional<ChunkProvenance> find(ChunkPosition chunk) throws ChunkIndexException {
      requireUsable();
      return Optional.ofNullable(SqliteIndex.this.find(connection, chunk));
    }

    @Override
    public ChunkCursor scan(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      Integer dim = dimensionIds.get(query.dimension());
      if (dim == null) {
        return new ListCursor(this, List.of());
      }
      return new SqlCursor(this, connection, query, dim);
    }

    @Override
    public long count(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      long[] stats = aggregate(query);
      return stats[0];
    }

    @Override
    public ChunkEnvelope envelope(ChunkQuery query) throws ChunkIndexException {
      requireUsable();
      long[] stats = aggregate(query);
      if (stats[0] == 0) {
        return ChunkEnvelope.empty(query.dimension());
      }
      return new ChunkEnvelope.Bounded(
          new ChunkRectangle(
              query.dimension(), (int) stats[1], (int) stats[2], (int) stats[3], (int) stats[4]));
    }

    private long[] aggregate(ChunkQuery query) throws ChunkIndexException {
      Integer dim = dimensionIds.get(query.dimension());
      if (dim == null) {
        return new long[] {0, 0, 0, 0, 0};
      }
      List<Object> params = new ArrayList<>();
      String where = where(query, dim, params, null);
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT count(*), min(c.x), min(c.z), max(c.x), max(c.z) FROM chunk c WHERE "
                  + where)) {
        bind(ps, params);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return new long[] {
            rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)
          };
        }
      } catch (SQLException e) {
        throw failure(e);
      }
    }

    @Override
    void release() {
      forgetStatements(connection);
      try {
        connection.rollback();
        connection.close();
      } catch (SQLException ignored) {
        // A read transaction has nothing to lose.
      }
    }
  }

  /**
   * Builds the WHERE clause of {@code query}. When {@code column} is non-null, the key range is
   * restricted to that region column so a wide rectangle does not scan unrelated columns.
   */
  private String where(ChunkQuery query, int dim, List<Object> params, @Nullable Integer column) {
    StringBuilder sql = new StringBuilder("c.dim = ?");
    params.add(dim);
    ChunkPosition after = query.startAfter();
    if (after != null) {
      sql.append(" AND c.k > ?");
      params.add(ChunkOrder.key(after));
    }
    ChunkRectangle area = query.area();
    if (area != null) {
      int rx0 = column != null ? column : area.minX() >> 5;
      int rx1 = column != null ? column : area.maxX() >> 5;
      sql.append(" AND c.k BETWEEN ? AND ? AND c.x BETWEEN ? AND ? AND c.z BETWEEN ? AND ?");
      params.add(ChunkOrder.regionStart(rx0, area.minZ() >> 5));
      params.add(ChunkOrder.regionEnd(rx1, area.maxZ() >> 5));
      params.add(area.minX());
      params.add(area.maxX());
      params.add(area.minZ());
      params.add(area.maxZ());
    }
    switch (query.protection()) {
      case ANY -> {}
      case PROTECTED -> sql.append(" AND c.protected = 1");
      case UNPROTECTED -> sql.append(" AND c.protected = 0");
    }
    switch (query.seasons()) {
      case SeasonFilter.Any any -> {}
      case SeasonFilter.Legacy legacy -> sql.append(" AND c.legacy = 1");
      case SeasonFilter.FirstObservedIn f -> {
        sql.append(" AND c.first_season = ?");
        params.add(seasonIds.getOrDefault(f.season(), -1));
      }
      case SeasonFilter.ActiveIn a -> {
        sql.append(" AND c.k IN (SELECT a2.k FROM activity a2 WHERE a2.season = ? AND a2.dim = ?)");
        params.add(seasonIds.getOrDefault(a.season(), -1));
        params.add(dim);
      }
      case SeasonFilter.TouchedBy t -> {
        int season = seasonIds.getOrDefault(t.season(), -1);
        sql.append(
            " AND (c.first_season = ? OR c.k IN (SELECT a2.k FROM activity a2"
                + " WHERE a2.season = ? AND a2.dim = ?))");
        params.add(season);
        params.add(season);
        params.add(dim);
      }
    }
    return sql.toString();
  }

  private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      Object p = params.get(i);
      if (p instanceof Integer n) {
        ps.setInt(i + 1, n);
      } else {
        ps.setLong(i + 1, (Long) p);
      }
    }
  }

  /** Streams rows, grouping the activity rows of one chunk; one statement per region column. */
  private final class SqlCursor extends CursorBase {
    private final Connection connection;
    private final ChunkQuery query;
    private final int dim;
    private final List<@Nullable Integer> columns = new ArrayList<>();
    private int nextColumn;
    private @Nullable PreparedStatement statement;
    private @Nullable ResultSet rows;
    private boolean rowPending;

    SqlCursor(ReaderBase reader, Connection connection, ChunkQuery query, int dim) {
      super(reader);
      this.connection = connection;
      this.query = query;
      this.dim = dim;
      ChunkRectangle area = query.area();
      if (area != null && (long) (area.maxX() >> 5) - (area.minX() >> 5) <= 256) {
        for (int rx = area.minX() >> 5; rx <= area.maxX() >> 5; rx++) {
          columns.add(rx);
        }
      } else {
        columns.add(null);
      }
    }

    @Override
    @Nullable ChunkProvenance advance() throws ChunkIndexException {
      try {
        while (true) {
          ResultSet rs = rows;
          if (rs != null && (rowPending || rs.next())) {
            rowPending = false;
            return readGroup(rs, query.dimension(), () -> rowPending = true);
          }
          closeStatement();
          if (nextColumn >= columns.size()) {
            return null;
          }
          List<Object> params = new ArrayList<>();
          String sql =
              SELECT
                  + " WHERE "
                  + where(query, dim, params, columns.get(nextColumn++))
                  + " ORDER BY c.k";
          PreparedStatement ps = connection.prepareStatement(sql);
          statement = ps;
          bind(ps, params);
          rows = ps.executeQuery();
        }
      } catch (SQLException e) {
        throw failure(e);
      }
    }

    private void closeStatement() throws SQLException {
      PreparedStatement ps = statement;
      if (ps != null) {
        ps.close();
      }
      statement = null;
      rows = null;
    }

    @Override
    void release() {
      try {
        closeStatement();
      } catch (SQLException ignored) {
        // Read-only.
      }
    }
  }

  private static final class ListCursor extends CursorBase {
    private final List<ChunkProvenance> records;
    private int next;

    ListCursor(ReaderBase reader, List<ChunkProvenance> records) {
      super(reader);
      this.records = records;
    }

    @Override
    @Nullable ChunkProvenance advance() {
      return next < records.size() ? records.get(next++) : null;
    }
  }

  /**
   * Reads one chunk's rows starting at the current row. Leaves the result set on the first row of
   * the next chunk and reports that through {@code pending}, or exhausted.
   */
  private ChunkProvenance readGroup(ResultSet rs, DimensionKey dimension, Runnable pending)
      throws SQLException, ChunkIndexException {
    long k = rs.getLong(1);
    boolean legacy = rs.getInt(2) == 1;
    int firstSeason = rs.getInt(3);
    Instant firstAt = Instant.ofEpochSecond(rs.getLong(4), rs.getLong(5));
    ProvenanceEvidence firstEvidence = EvidenceCodes.decode(rs.getInt(6));
    boolean isProtected = rs.getInt(7) == 1;
    List<SeasonActivity> activity = new ArrayList<>();
    do {
      if (rs.getLong(1) != k) {
        pending.run();
        break;
      }
      rs.getInt(8);
      if (!rs.wasNull()) {
        activity.add(
            new SeasonActivity(
                season(rs.getInt(8)),
                Instant.ofEpochSecond(rs.getLong(9), rs.getLong(10)),
                EvidenceCodes.decode(rs.getInt(11))));
      }
    } while (rs.next());
    FirstObservation first =
        legacy
            ? new FirstObservation.Legacy(firstAt, firstEvidence)
            : new FirstObservation.InSeason(season(firstSeason), firstAt, firstEvidence);
    return new ChunkProvenance(
        ChunkOrder.position(dimension, k),
        first,
        activity,
        isProtected ? ChunkProtection.PROTECTED : ChunkProtection.NONE);
  }

  private @Nullable ChunkProvenance find(Connection connection, ChunkPosition chunk)
      throws ChunkIndexException {
    Integer dim = dimensionIds.get(chunk.dimension());
    if (dim == null) {
      return null;
    }
    try {
      PreparedStatement ps = findStatement(connection);
      ps.setInt(1, dim);
      ps.setLong(2, ChunkOrder.key(chunk));
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? readGroup(rs, chunk.dimension(), () -> {}) : null;
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  /** Point lookups reuse one prepared statement per connection, as any real caller would. */
  private final Map<Connection, PreparedStatement> findStatements = new HashMap<>();

  private synchronized PreparedStatement findStatement(Connection connection) throws SQLException {
    PreparedStatement ps = findStatements.get(connection);
    if (ps == null) {
      ps = connection.prepareStatement(SELECT + " WHERE c.dim = ? AND c.k = ?");
      findStatements.put(connection, ps);
    }
    return ps;
  }

  private synchronized void forgetStatements(Connection connection) {
    findStatements.remove(connection);
  }

  private SeasonId season(int id) throws ChunkIndexException {
    SeasonId season = seasonsById.get(id);
    if (season == null) {
      throw SpikeCodes.corrupt("unknown season id " + id);
    }
    return season;
  }

  private static ChunkIndexException failure(SQLException e) {
    String message = String.valueOf(e.getMessage());
    return message.contains("malformed")
            || message.contains("not a database")
            || message.contains("CORRUPT")
        ? SpikeCodes.corrupt("SQLite reports corruption: " + message, e)
        : SpikeCodes.io("SQLite failure: " + message, e);
  }

  // ---- writing -------------------------------------------------------------------------------

  @Override
  synchronized @Nullable ChunkProvenance committedRecord(ChunkPosition chunk)
      throws ChunkIndexException {
    return find(writer, chunk);
  }

  @Override
  synchronized void commitChanges(Map<ChunkPosition, @Nullable ChunkProvenance> changes)
      throws ChunkIndexException {
    Map<DimensionKey, Integer> newDimensions = new HashMap<>();
    Map<SeasonId, Integer> newSeasons = new HashMap<>();
    try (PreparedStatement deleteActivity =
            writer.prepareStatement("DELETE FROM activity WHERE dim = ? AND k = ?");
        PreparedStatement deleteChunk =
            writer.prepareStatement("DELETE FROM chunk WHERE dim = ? AND k = ?");
        PreparedStatement upsertChunk =
            writer.prepareStatement("INSERT OR REPLACE INTO chunk VALUES (?,?,?,?,?,?,?,?,?,?)");
        PreparedStatement insertActivity =
            writer.prepareStatement("INSERT INTO activity VALUES (?,?,?,?,?,?)")) {
      for (Map.Entry<ChunkPosition, @Nullable ChunkProvenance> change : changes.entrySet()) {
        ChunkPosition chunk = change.getKey();
        int dim = dimensionId(chunk.dimension(), newDimensions);
        long k = ChunkOrder.key(chunk);
        deleteActivity.setInt(1, dim);
        deleteActivity.setLong(2, k);
        deleteActivity.addBatch();
        ChunkProvenance r = change.getValue();
        if (r == null) {
          deleteChunk.setInt(1, dim);
          deleteChunk.setLong(2, k);
          deleteChunk.addBatch();
          continue;
        }
        FirstObservation first = r.firstObservation();
        SeasonId firstSeason = first.knownSeason();
        upsertChunk.setInt(1, dim);
        upsertChunk.setLong(2, k);
        upsertChunk.setInt(3, chunk.x());
        upsertChunk.setInt(4, chunk.z());
        upsertChunk.setInt(5, r.isLegacy() ? 1 : 0);
        if (firstSeason == null) {
          upsertChunk.setNull(6, java.sql.Types.INTEGER);
        } else {
          upsertChunk.setInt(6, seasonId(firstSeason, newSeasons));
        }
        Instant firstAt = first.observedAt();
        upsertChunk.setLong(7, firstAt.getEpochSecond());
        upsertChunk.setLong(8, firstAt.getNano());
        upsertChunk.setInt(9, EvidenceCodes.encode(first.evidence()));
        upsertChunk.setInt(10, r.protection() == ChunkProtection.PROTECTED ? 1 : 0);
        upsertChunk.addBatch();
        for (SeasonActivity a : r.activity()) {
          insertActivity.setInt(1, dim);
          insertActivity.setLong(2, k);
          insertActivity.setInt(3, seasonId(a.season(), newSeasons));
          Instant at = a.lastObservedAt();
          insertActivity.setLong(4, at.getEpochSecond());
          insertActivity.setLong(5, at.getNano());
          insertActivity.setInt(6, EvidenceCodes.encode(a.evidence()));
          insertActivity.addBatch();
        }
      }
      deleteActivity.executeBatch();
      deleteChunk.executeBatch();
      upsertChunk.executeBatch();
      insertActivity.executeBatch();
      writer.commit();
    } catch (SQLException | RuntimeException e) {
      try {
        writer.rollback();
      } catch (SQLException ignored) {
        // The original failure is reported.
      }
      newDimensions.keySet().forEach(d -> dimensionsById.remove(dimensionIds.remove(d)));
      newSeasons.keySet().forEach(s -> seasonsById.remove(seasonIds.remove(s)));
      throw e instanceof SQLException sql ? failure(sql) : SpikeCodes.io("commit failed", e);
    }
  }

  private int dimensionId(DimensionKey key, Map<DimensionKey, Integer> created)
      throws SQLException {
    Integer id = dimensionIds.get(key);
    if (id != null) {
      return id;
    }
    int next = dimensionIds.size() + 1;
    try (PreparedStatement ps = writer.prepareStatement("INSERT INTO dimension VALUES (?, ?)")) {
      ps.setInt(1, next);
      ps.setString(2, key.namespacedId());
      ps.executeUpdate();
    }
    dimensionIds.put(key, next);
    dimensionsById.put(next, key);
    created.put(key, next);
    return next;
  }

  private int seasonId(SeasonId season, Map<SeasonId, Integer> created) throws SQLException {
    Integer id = seasonIds.get(season);
    if (id != null) {
      return id;
    }
    int next = seasonIds.size() + 1;
    try (PreparedStatement ps = writer.prepareStatement("INSERT INTO season VALUES (?, ?, ?)")) {
      ps.setInt(1, next);
      ps.setLong(2, season.value().getMostSignificantBits());
      ps.setLong(3, season.value().getLeastSignificantBits());
      ps.executeUpdate();
    }
    seasonIds.put(season, next);
    seasonsById.put(next, season);
    created.put(season, next);
    return next;
  }

  @Override
  public synchronized void verify() throws ChunkIndexException {
    requireOpen();
    try (Statement s = writer.createStatement();
        ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
      List<String> problems = new ArrayList<>();
      while (rs.next()) {
        problems.add(rs.getString(1));
      }
      writer.rollback();
      if (!problems.equals(List.of("ok"))) {
        throw SpikeCodes.corrupt("integrity_check: " + problems);
      }
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  @Override
  synchronized void closeStorage() {
    try {
      writer.rollback();
      writer.close();
    } catch (SQLException ignored) {
      // Everything committed is durable.
    }
  }
}
