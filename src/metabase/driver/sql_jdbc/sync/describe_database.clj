(ns metabase.driver.sql-jdbc.sync.describe-database
  "SQL JDBC impl for `describe-database`."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.driver.sql-jdbc
             [connection :as sql-jdbc.conn]
             [execute :as sql-jdbc.execute]]
            [metabase.driver.sql-jdbc.sync
             [common :as common]
             [interface :as i]])
  (:import [java.sql Connection DatabaseMetaData]))

(defmethod i/excluded-schemas :sql-jdbc [_] nil)

(defn- all-schemas [^DatabaseMetaData metadata]
  {:pre [(instance? DatabaseMetaData metadata)]}
  (reify clojure.lang.IReduceInit
    (reduce [_ rf init]
      (with-open [rs (.getSchemas metadata)]
        (transduce
         (map :table_schem)
         rf
         init
         (jdbc/reducible-result-set rs {}))))))

(defn- syncable-schemas
  [driver metadata]
  (eduction (remove (set (i/excluded-schemas driver)))
            (all-schemas metadata)))

(defn- execute-select-probe-query
  "Execute the simple SELECT query defined above. The main goal here is to check whether we're able to execute a SELECT
  query against the Table in question -- we don't care about the results themselves -- so the query and the logic
  around executing it should be as simple as possible. We need to highly optimize this logic because it's executed for
  every Table on every sync."
  [driver ^Connection conn [sql & params]]
  {:pre [(string? sql)]}
  (with-open [stmt (common/prepare-statement driver conn sql params)]
    ;; attempting to execute the SQL statement will throw an Exception if we don't have permissions; otherwise it will
    ;; truthy wheter or not it returns a ResultSet, but we can ignore that since we have enough info to proceed at
    ;; this point.
    (.execute stmt)))

(defmethod i/have-select-privilege? :sql-jdbc
  [driver conn table-schema table-name]
  ;; Query completes = we have SELECT privileges
  ;; Query throws some sort of no permissions exception = no SELECT privileges
  (u/ignore-exceptions
    (execute-select-probe-query driver conn
                                (common/simple-select-probe-query driver table-schema table-name))
    true))

(defn- db-tables
  "Fetch a JDBC Metadata ResultSet of tables in the DB, optionally limited to ones belonging to a given
  schema. Returns a reducible sequence of results."
  [driver ^DatabaseMetaData metadata ^String schema-or-nil ^String db-name-or-nil]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ rf init]
      ;; tablePattern "%" = match all tables
      (with-open [rs (.getTables metadata db-name-or-nil (driver/escape-entity-name-for-metadata driver schema-or-nil) "%"
                                 (into-array String ["TABLE" "VIEW" "FOREIGN TABLE" "MATERIALIZED VIEW" "EXTERNAL TABLE"]))]
        (reduce
         rf
         init
         (eduction (map #(select-keys % [:table_name :remarks :table_schem]))
                   (jdbc/reducible-result-set rs {})))))))

(defn fast-active-tables
  "Default, fast implementation of `active-tables` best suited for DBs with lots of system tables (like Oracle). Fetch
  list of schemas, then for each one not in `excluded-schemas`, fetch its Tables, and combine the results.

  This is as much as 15x faster for Databases with lots of system tables than `post-filtered-active-tables` (4 seconds
  vs 60)."
  [driver ^Connection conn & [db-name-or-nil]]
  {:pre [(instance? Connection conn)]}
  (let [metadata (.getMetaData conn)]
    (eduction
     (comp (map (fn [schema]
                  (db-tables driver metadata schema db-name-or-nil)))
           cat
           (filter (fn [{table-schema :table_schem, table-name :table_name}]
                     (i/have-select-privilege? driver conn table-schema table-name))))
     (syncable-schemas driver metadata))))

(defmethod i/active-tables :sql-jdbc
  [driver connection]
  (fast-active-tables driver connection))

(defn post-filtered-active-tables
  "Alternative implementation of `active-tables` best suited for DBs with little or no support for schemas. Fetch *all*
  Tables, then filter out ones whose schema is in `excluded-schemas` Clojure-side."
  [driver ^Connection conn & [db-name-or-nil]]
  {:pre [(instance? Connection conn)]}
  (eduction
   (filter (let [excluded (i/excluded-schemas driver)]
             (fn [{table-schema :table_schem, table-name :table_name}]
               (and (not (contains? excluded table-schema))
                    (i/have-select-privilege? driver conn table-schema table-name)))))
   (db-tables driver (.getMetaData conn) nil db-name-or-nil)))

(defn describe-database
  "Default implementation of `driver/describe-database` for SQL JDBC drivers. Uses JDBC DatabaseMetaData."
  [driver db-or-id-or-spec]
  {:tables (with-open [conn (jdbc/get-connection (sql-jdbc.conn/db->pooled-connection-spec db-or-id-or-spec))]
             ;; try to set the Connection to `READ_UNCOMMITED` if possible, or whatever the next least-locking level
             ;; is. Not sure how much of a difference that makes since we're not running this inside a transaction,
             ;; but better safe than sorry
             (sql-jdbc.execute/set-best-transaction-level! driver conn)
             (transduce
              (map (fn [table]
                     {:name        (:table_name table)
                      :schema      (:table_schem table)
                      :description (let [remarks (:remarks table)]
                                     (when-not (str/blank? remarks)
                                       remarks))}))
              conj
              #{}
              (i/active-tables driver conn)))})
