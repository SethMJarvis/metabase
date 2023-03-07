(ns metabase.db.liquibase.h2
  "Custom implementation of the Liquibase H2 adapter that uppercases all identifiers. See #20611 for more details."
  (:require
   [metabase.util :as u])
  (:import
   (liquibase.database.core H2Database)
   (liquibase.database.jvm JdbcConnection)))

(set! *warn-on-reflection* true)

(defn- upcase ^String [s]
  (some-> s u/upper-case-en))

(defn- create-udfs!
  "Registers the JSON manipulation functions for an H2 database."
  [^JdbcConnection conn]
  (.execute (.createStatement conn) "
CREATE ALIAS IF NOT EXISTS JSON_VALUE AS $$
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.JSONArray;
@CODE
String jsonStringValue(String s, String key) {
    Object obj = JSONValue.parse(s);
    if (obj instanceof JSONObject) {
        JSONObject jsonObject = (JSONObject) obj;
        if (jsonObject.containsKey(key)) {
            return jsonObject.get(key).toString();
        } else {
            return null;
        }
    } else if (obj instanceof JSONArray) {
        JSONArray jsonArray = (JSONArray) obj;
        int keyInt = Integer.parseInt(key);
        if (keyInt < jsonArray.size() && keyInt >= 0) {
            return jsonArray.get(keyInt).toString();
        } else {
            return null;
        }
    } else {
        return null;
    }
}
$$;"))

(defn- h2-database* ^H2Database []
  (proxy [H2Database] []
    (quoteObject [object-name object-type]
      (let [^H2Database this this]
        (proxy-super quoteObject (upcase object-name) object-type)))

    (mustQuoteObjectName [_object-name _object-type]
      true)))

;; HACK! Create a [[java.lang.Package]] for the proxy class if one does not already exist. This is needed because:
;;
;; 1. Liquibase will throw an NPE if the package for the class does not exist -- see
;;    https://github.com/liquibase/liquibase/blob/master/liquibase-core/src/main/java/liquibase/logging/core/JavaLogService.java#L45
;;    and https://github.com/liquibase/liquibase/issues/2633
;;
;; 2. In Java 9+, the JVM will automatically define a package when a class is created; in Java 8, it does not.
;;
;; 3. The Clojure DynamicClassLoader does not create a Package -- see
;;    https://clojure.atlassian.net/browse/CLJ-1550?focusedCommentId=13025
;;
;; This only does anything in REPL-based development; in the uberjar the proxy class will be AOT'ed and will have a
;; package defined for it when it's loaded by the normal JVM classloader rather than the Clojure DynamicClassLoader
(let [klass (class (h2-database*))]
  (when-not (.getPackage klass)
    (let [method       (.getDeclaredMethod
                        ClassLoader
                        "definePackage"
                        (into-array Class [String String String String String String String java.net.URL]))
          class-name   (.getName klass)
          ;; e.g. metabase.db.liquibase.h2.proxy$liquibase.database.core
          package-name (.substring class-name 0 (.lastIndexOf class-name "."))]
      (doto method
        (.setAccessible true)
        (.invoke (.getClassLoader klass) (into-array Object [package-name nil nil nil nil nil nil nil]))
        (.setAccessible false))
      (assert (.getPackage klass) (format "Failed to create package for proxy class %s." class-name)))))

(defn h2-database
  "A version of the Liquibase H2 implementation that always converts identifiers to uppercase and then quotes them.
   Also creates UDFs for JSON handling."
  ^H2Database [^JdbcConnection conn]
  (create-udfs! conn)
  (doto (h2-database*)
    (.setConnection conn)))
