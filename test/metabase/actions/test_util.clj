(ns metabase.actions.test-util
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.http-client :as client]
   [metabase.models :refer [Action Card Database]]
   [metabase.models.action :as action]
   [metabase.query-processor-test :as qp.test]
   [metabase.test.data :as data]
   [metabase.test.data.datasets :as datasets]
   [metabase.test.initialize :as initialize]
   [metabase.test.util :as tu]
   [toucan.util.test :as tt]
   [toucan.db :as db]
   [metabase.driver :as driver]
   [metabase.test.data.interface :as tx]))

(def ^:dynamic ^:private ^:deprecated *actions-test-data-tables*
  #{"categories"})

(defn do-with-actions-test-data-tables
  "Impl for [[with-actions-test-data-tables]]."
  [tables thunk]
  ;; make sure all the table names are valid so we can catch errors/typos
  (let [valid-table-names-set #{"users" "venues" "checkins" "categories"}]
    (doseq [table-name tables]
      (assert (contains? valid-table-names-set table-name)
              (format "Invalid table for `with-actions-test-data-tables` %s. Valid tables: %s"
                      (pr-str table-name)
                      (pr-str valid-table-names-set)))))
  (binding [*actions-test-data-tables* (set tables)]
    (thunk)))

(defmacro ^:deprecated with-actions-test-data-tables
  "Override the tables that should be included in the [[actions-test-data]] test data DB when
  using [[with-actions-test-data]]. Normally only the `categories` table is included for maximum speed since this is
  usually enough to test things. Sometimes, however, you need some of the other tables, e.g. to test FK constraints
  failures:

    ;; using categories AND venues will let us test FK constraint failures
    (actions.test-util/with-actions-test-data-tables #{\"categories\" \"venues\"}
      (actions.test-util/with-actions-test-data
        ...))

  Note that [[with-actions-test-data-tables]] needs to wrap [[with-actions-test-data]]; it won't work the other way
  around."
  {:style/indent 1}
  [tables & body]
  `(do-with-actions-test-data-tables ~tables (^:once fn* [] ~@body)))

(defn do-with-actions-test-data
  "Impl for [[with-actions-test-data]]."
  [thunk]
  (data/dataset :test-data
    (try
      (thunk)
      (finally
        ;; delete the test data database so it gets recreated. NOT IDEAL! FIXME!
        (db/delete! Database :engine (name (tx/driver)), :name "test-data")))))

(defmacro with-actions-test-data
  "Sets the current dataset to a freshly-loaded copy of [[defs/test-data]] that only includes the `categories` table
  that gets destroyed at the conclusion of `body`. Use this to test destructive actions that may modify the data."
  {:style/indent 0}
  [& body]
  `(do-with-actions-test-data (fn [] ~@body)))

(defmacro with-temp-test-data
  "Sets the current dataset to a freshly created dataset-definition that gets destroyed at the conclusion of `body`.
   Use this to test destructive actions that may modify the data."
  {:style/indent 0}
  [_dataset-definition & body]
  `(do
     (println "FIXME: metabase.actions.test-util/with-temp-test-data")
     (println ~(format "FIXME: %s" (ns-name *ns*)))
     ~@body)
  ;; `(do-with-dataset-definition (tx/dataset-definition ~(str (gensym)) ~dataset-definition) (fn [] ~@body))
  )

(deftest with-actions-test-data-test
  (datasets/test-drivers (qp.test/normal-drivers-with-feature :actions/custom)
    (dotimes [i 2]
      (testing (format "Iteration %d" i)
        (with-actions-test-data
          (letfn [(row-count []
                    (qp.test/rows (data/run-mbql-query categories {:aggregation [[:count]]})))]
            (testing "before"
              (is (= [[75]]
                     (row-count))))
            (testing "delete row"
              (is (= [1]
                     (jdbc/execute! (sql-jdbc.conn/db->pooled-connection-spec (data/id))
                                    "DELETE FROM CATEGORIES WHERE ID = 1;"))))
            (testing "after"
              (is (= [[74]]
                     (row-count))))))))))

(defn do-with-action
  "Impl for [[with-action]]."
  [options-map model-id]
  (case (:type options-map)
    :query
    (let [action-id (action/insert!
                     (merge {:model_id model-id
                             :name "Query Example"
                             :parameters [{:id "id"
                                           :slug "id"
                                           :type "number"
                                           :target [:variable [:template-tag "id"]]}
                                          {:id "name"
                                           :slug "name"
                                           :type "text"
                                           :required false
                                           :target [:variable [:template-tag "name"]]}]
                             :visualization_settings {:inline true}
                             :database_id (data/id)
                             :dataset_query {:database (data/id)
                                             :type :native
                                             :native {:query (str "UPDATE categories\n"
                                                                  "SET name = concat([[{{name}}, ' ',]] 'Sh', 'op')\n"
                                                                  "WHERE id = {{id}}")
                                                      :template-tags {"id" {:name         "id"
                                                                            :display-name "ID"
                                                                            :type         :number
                                                                            :required     true}
                                                                      "name" {:name         "name"
                                                                              :display-name "Name"
                                                                              :type         :text
                                                                              :required     false}}}}}
                            options-map))]
      {:action-id action-id :model-id model-id})
    :implicit
    (let [action-id (action/insert! (merge
                                     {:type :implicit
                                      :name "Update Example"
                                      :kind "row/update"
                                      :model_id model-id}
                                     options-map))]
      {:action-id action-id :model-id model-id})

    :http
    (let [action-id (action/insert! (merge
                                     {:type :http
                                      :name "Echo Example"
                                      :template {:url (client/build-url "testing/echo[[?fail={{fail}}]]" {})
                                                 :method "POST"
                                                 :body "{\"the_parameter\": {{id}}}"
                                                 :headers "{\"x-test\": \"{{id}}\"}"}
                                      :parameters [{:id "id"
                                                    :type "number"
                                                    :target [:template-tag "id"]}
                                                   {:id "fail"
                                                    :type "text"
                                                    :target [:template-tag "fail"]}]
                                      :response_handle ".body"
                                      :model_id model-id}
                                     options-map))]
      {:action-id action-id :model-id model-id})))

(defmacro with-actions
  "Execute `body` with newly created Actions.
  `binding-forms-and-options-maps` is a vector of even number of elements, binding and options-map,
  similar to a `let` form.
  The first two elements of `binding-forms-and-options-maps` can describe the model, for this the
  first option-map should map :dataset to a truthy value and contain :dataset_query. In this case
  the first binding is bound to the model card created.
  For actions, the binding form is bound to a map with :action-id and :model-id set to the ID of
  the created action and model card respectively. The options-map overrides the defaults in
  `do-with-action`.

  (with-actions [{model-card-id :id} {:dataset true :dataset_query (mt/mbql-query types)}
                 {id :action-id} {}
                 {:keys [action-id model-id]} {:type :http :name \"Temp HTTP Action\"}]
    (assert (= model-card-id model-id))
    (something model-card-id id action-id model-id))"
  {:style/indent 1}
  [binding-forms-and-option-maps & body]
  (assert (vector? binding-forms-and-option-maps)
          "binding-forms-and-option-maps should be a vector")
  (assert (even? (count binding-forms-and-option-maps))
          "binding-forms-and-option-maps should have an even number of elements")
  (let [model (gensym "model-")
        [_ maybe-model-def :as model-part] (subvec binding-forms-and-option-maps 0 2)
        [[custom-binding model-def] binding-forms-and-option-maps]
        (if (and (map? maybe-model-def)
                 (:dataset maybe-model-def)
                 (contains? maybe-model-def :dataset_query))
          [model-part (drop 2 binding-forms-and-option-maps)]
          ['[_ {:dataset true :dataset_query (metabase.test.data/mbql-query categories)}]
           binding-forms-and-option-maps])]
    `(do
       (initialize/initialize-if-needed! :web-server)
       (tt/with-temp Card ~[model model-def]
         (tu/with-model-cleanup [Action]
           (let [~custom-binding ~model
                 ~@(mapcat (fn [[binding-form option-map]]
                             [binding-form `(do-with-action (merge {:type :query} ~option-map) (:id ~model))])
                           (partition 2 binding-forms-and-option-maps))]
             ~@body))))))

(comment
  (with-actions [{id :action-id} {:type :implicit :kind "row/create"}
                 {:keys [action-id model-id]} {:type :http}]
    (something id action-id model-id))
  (with-actions [{model-card-id :id} {:dataset true :dataset_query (data/mbql-query types)}
                 {id :action-id} {:type :implicit :kind "row/create"}
                 {:keys [action-id model-id]} {}]
    (something model-card-id id action-id model-id))
  nil)

(defn do-with-actions-enabled
  "Impl for [[with-actions-enabled]]."
  [thunk]
  (tu/with-temporary-setting-values [experimental-enable-actions true]
    (tu/with-temp-vals-in-db Database (data/id) {:settings {:database-enable-actions true}}
      (thunk))))

(defmacro with-actions-enabled
  "Execute `body` with Actions enabled at the global level and for the current test Database."
  {:style/indent 0}
  [& body]
  `(do-with-actions-enabled (fn [] ~@body)))

(defmacro with-actions-test-data-and-actions-enabled
  "Combines [[with-actions-test-data]] and [[with-actions-enabled]]."
  {:style/indent 0}
  [& body]
  `(with-actions-test-data
     (with-actions-enabled
       ~@body)))
