(ns metabase.api.automagic-dashboards
  (:require
   [buddy.core.codecs :as codecs]
   [cheshire.core :as json]
   [compojure.core :refer [GET]]
   [metabase.api.common :as api]
   [metabase.automagic-dashboards.comparison :refer [comparison-dashboard]]
   [metabase.automagic-dashboards.core :as magic]
   [metabase.automagic-dashboards.core
    :refer [automagic-analysis candidate-tables]]
   [metabase.automagic-dashboards.rules :as rules]
   [metabase.models.card :refer [Card]]
   [metabase.models.collection :refer [Collection]]
   [metabase.models.database :refer [Database]]
   [metabase.models.field :refer [Field]]
   [metabase.models.metric :refer [Metric]]
   [metabase.models.model-index :refer [ModelIndex ModelIndexValue]]
   [metabase.models.permissions :as perms]
   [metabase.models.query :as query]
   [metabase.models.query.permissions :as query-perms]
   [metabase.models.segment :refer [Segment]]
   [metabase.models.table :refer [Table]]
   [metabase.transforms.dashboard :as transform.dashboard]
   [metabase.transforms.materialize :as tf.materialize]
   [metabase.util.i18n :refer [deferred-tru]]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   #_{:clj-kondo/ignore [:deprecated-namespace]}
   [metabase.util.schema :as su]
   [ring.util.codec :as codec]
   [schema.core :as s]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private Show
  (su/with-api-error-message (s/maybe (s/enum "all"))
    (deferred-tru "invalid show value")))

(def ^:private Prefix
  (su/with-api-error-message
      (s/pred (fn [prefix]
                (some #(not-empty (rules/get-rules [% prefix])) ["table" "metric" "field"])))
    (deferred-tru "invalid value for prefix")))

(def ^:private Rule
  (su/with-api-error-message
      (s/pred (fn [rule]
                (some (fn [toplevel]
                        (some (comp rules/get-rule
                                    (fn [prefix]
                                      [toplevel prefix rule])
                                    :rule)
                              (rules/get-rules [toplevel])))
                      ["table" "metric" "field"])))
    (deferred-tru "invalid value for rule name")))

(def ^:private ^{:arglists '([s])} decode-base64-json
  (comp #(json/decode % keyword) codecs/bytes->str codec/base64-decode))

(def ^:private Base64EncodedJSON
  (su/with-api-error-message
      (s/pred decode-base64-json)
    (deferred-tru "value couldn''t be parsed as base64 encoded JSON")))

(api/defendpoint GET "/database/:id/candidates"
  "Return a list of candidates for automagic dashboards orderd by interestingness."
  [id]
  {id ms/PositiveInt}
  (-> (t2/select-one Database :id id)
      api/read-check
      candidate-tables))

;; ----------------------------------------- API Endpoints for viewing a transient dashboard ----------------

(defn- adhoc-query-read-check
  [query]
  (api/check-403 (perms/set-has-partial-permissions-for-set?
                   @api/*current-user-permissions-set*
                   (query-perms/perms-set (:dataset_query query), :throw-exceptions? true)))
  query)

(defn- ensure-int
  [x]
  (if (string? x)
    (Integer/parseInt x)
    x))

(defmulti ^:private ->entity
  "Parse/decode/coerce string `s` an to an entity of `entity-type`. `s` is something like a unparsed integer row ID,
  encoded query, or transform name."
  {:arglists '([entity-type s])}
  (fn [entity-type _s]
    (keyword entity-type)))

(defmethod ->entity :table
  [_entity-type table-id-str]
  ;; table-id can also be a source query reference like `card__1` so in that case we should pull the ID out and use the
  ;; `:question` method instead
  (if-let [[_ card-id-str] (when (string? table-id-str)
                             (re-matches #"^card__(\d+$)" table-id-str))]
    (->entity :question card-id-str)
    (api/read-check (t2/select-one Table :id (ensure-int table-id-str)))))

(defmethod ->entity :segment
  [_entity-type segment-id-str]
  (api/read-check (t2/select-one Segment :id (ensure-int segment-id-str))))

(defmethod ->entity :model
  [_entity-type card-id-str]
  (api/read-check (t2/select-one Card
                    :id (ensure-int card-id-str)
                    :dataset true)))

(defmethod ->entity :question
  [_entity-type card-id-str]
  (api/read-check (t2/select-one Card :id (ensure-int card-id-str))))

(defmethod ->entity :adhoc
  [_entity-type encoded-query]
  (adhoc-query-read-check (query/adhoc-query (decode-base64-json encoded-query))))

(defmethod ->entity :metric
  [_entity-type metric-id-str]
  (api/read-check (t2/select-one Metric :id (ensure-int metric-id-str))))

(defmethod ->entity :field
  [_entity-type field-id-str]
  (api/read-check (t2/select-one Field :id (ensure-int field-id-str))))

(defmethod ->entity :transform
  [_entity-type transform-name]
  (api/read-check (t2/select-one Collection :id (tf.materialize/get-collection transform-name)))
  transform-name)

(def ^:private entities
  (map name (keys (methods ->entity))))

(def ^:private Entity
  (su/with-api-error-message
      (apply s/enum entities)
    (deferred-tru "Invalid entity type")))

(def ^:private ComparisonEntity
  (su/with-api-error-message
      (s/enum "segment" "adhoc" "table")
    (deferred-tru "Invalid comparison entity type. Can only be one of \"table\", \"segment\", or \"adhoc\"")))

(api/defendpoint GET "/:entity/:entity-id-or-query"
  "Return an automagic dashboard for entity `entity` with id `id`."
  [entity entity-id-or-query show]
  {show   [:maybe [:= "all"]]
   entity (mu/with-api-error-message
            (into [:enum] entities)
            (deferred-tru "Invalid entity type"))}
  (if (= entity "transform")
    (transform.dashboard/dashboard (->entity entity entity-id-or-query))
    (-> (->entity entity entity-id-or-query)
        (automagic-analysis {:show (keyword show)}))))

(defn linked-entities
  "stuff"
  [{{field-ref :pk_ref} :model-index {rsmd :result_metadata} :model}]
  (when-let [field-id (:id (some #(when ((comp #{field-ref} :field_ref) %) %) rsmd))]
    (when-let [table-ids (t2/select-fn-set :table_id 'Field :fk_target_field_id field-id)]
      (t2/select 'Table :id [:in table-ids]))))

(defn create-linked-dashboard
  "For each joinable table from `model`, create an x-ray dashboard as a tab."
  [{:keys [model linked-tables model-index model-index-value query-filter]}]
  (let [child-dashboards (map
                           #(magic/automagic-analysis % {:show :all
                                                         :query-filter query-filter})
                           linked-tables)
        dashboard-id     (gensym)
        dashboards       (->> child-dashboards
                              (map (fn [dashboard]
                                     (let [tab-id (gensym)]
                                       (-> dashboard
                                           (assoc :id tab-id)
                                           (update
                                             :ordered_cards
                                             (fn [ocs]
                                               (map (fn [oc] (assoc oc
                                                               :dashboard_id dashboard-id
                                                               :dashboard_tab_id tab-id)) ocs))))))))
        ordered-tabs     (map-indexed (fn [idx {tab-name :name tab-id :id}]
                                        {:id           tab-id
                                         :dashboard_id dashboard-id
                                         :name         tab-name
                                         :position     idx})
                                      dashboards)]
    (reduce
      (fn [acc {:keys [ordered_cards parameters]}]
        (update acc :ordered_cards concat ordered_cards))
      (assoc
        (first child-dashboards)
        :ordered_tabs ordered-tabs)
      (rest child-dashboards))))

(api/defendpoint GET "/model_index/:model-index-id/primary_key/:pk-id"
  "Return an automagic dashboard for an entity detail specified by `entity`
  with id `id` and a primary key of `indexed-value`."
  [model-index-id pk-id]
  {model-index-id :int
   pk-id          :int}
  ;; Stuff...
  (api/let-404 [model-index       (t2/select-one ModelIndex :id model-index-id)
                model             (t2/select-one Card (:model_id model-index))
                model-index-value (t2/select-one ModelIndexValue
                                                 :model_index_id model-index-id
                                                 :model_pk pk-id)
                linked            (linked-entities {:model             model
                                                    :model-index       model-index
                                                    :model-index-value model-index-value})]
               ;; `->entity` does a read check on the model but this is here as well to be extra sure.
               (api/read-check Card (:model_id model-index))
               (or (create-linked-dashboard {:model model
                                             :linked-tables linked
                                             :model-index model-index
                                             :model-index-value model-index-value
                                             :query-filter [:= (:pk_ref model-index) pk-id]})
                   (throw (ex-info "No linked entities" {:model-index-id model-index-id})))))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/rule/:prefix/:rule"
  "Return an automagic dashboard for entity `entity` with id `id` using rule `rule`."
  [entity entity-id-or-query prefix rule show]
  {entity Entity
   show   Show
   prefix Prefix
   rule   Rule}
  (-> (->entity entity entity-id-or-query)
      (automagic-analysis {:show (keyword show)
                           :rule ["table" prefix rule]})))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/cell/:cell-query"
  "Return an automagic dashboard analyzing cell in  automagic dashboard for entity `entity`
   defined by
   query `cell-querry`."
  [entity entity-id-or-query cell-query show]
  {entity     Entity
   show       Show
   cell-query Base64EncodedJSON}
  (-> (->entity entity entity-id-or-query)
      (automagic-analysis {:show       (keyword show)
                           :cell-query (decode-base64-json cell-query)})))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/cell/:cell-query/rule/:prefix/:rule"
  "Return an automagic dashboard analyzing cell in question  with id `id` defined by
   query `cell-querry` using rule `rule`."
  [entity entity-id-or-query cell-query prefix rule show]
  {entity     Entity
   show       Show
   prefix     Prefix
   rule       Rule
   cell-query Base64EncodedJSON}
  (-> (->entity entity entity-id-or-query)
      (automagic-analysis {:show       (keyword show)
                           :rule       ["table" prefix rule]
                           :cell-query (decode-base64-json cell-query)})))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/compare/:comparison-entity/:comparison-entity-id-or-query"
  "Return an automagic comparison dashboard for entity `entity` with id `id` compared with entity
   `comparison-entity` with id `comparison-entity-id-or-query.`"
  [entity entity-id-or-query show comparison-entity comparison-entity-id-or-query]
  {show              Show
   entity            Entity
   comparison-entity ComparisonEntity}
  (let [left      (->entity entity entity-id-or-query)
        right     (->entity comparison-entity comparison-entity-id-or-query)
        dashboard (automagic-analysis left {:show         (keyword show)
                                            :query-filter nil
                                            :comparison?  true})]
    (comparison-dashboard dashboard left right {})))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/rule/:prefix/:rule/compare/:comparison-entity/:comparison-entity-id-or-query"
  "Return an automagic comparison dashboard for entity `entity` with id `id` using rule `rule`;
   compared with entity `comparison-entity` with id `comparison-entity-id-or-query.`."
  [entity entity-id-or-query prefix rule show comparison-entity comparison-entity-id-or-query]
  {entity            Entity
   show              Show
   prefix            Prefix
   rule              Rule
   comparison-entity ComparisonEntity}
  (let [left      (->entity entity entity-id-or-query)
        right     (->entity comparison-entity comparison-entity-id-or-query)
        dashboard (automagic-analysis left {:show         (keyword show)
                                            :rule         ["table" prefix rule]
                                            :query-filter nil
                                            :comparison?  true})]
    (comparison-dashboard dashboard left right {})))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/cell/:cell-query/compare/:comparison-entity/:comparison-entity-id-or-query"
  "Return an automagic comparison dashboard for cell in automagic dashboard for entity `entity`
   with id `id` defined by query `cell-querry`; compared with entity `comparison-entity` with id
   `comparison-entity-id-or-query.`."
  [entity entity-id-or-query cell-query show comparison-entity comparison-entity-id-or-query]
  {entity            Entity
   show              Show
   cell-query        Base64EncodedJSON
   comparison-entity ComparisonEntity}
  (let [left      (->entity entity entity-id-or-query)
        right     (->entity comparison-entity comparison-entity-id-or-query)
        dashboard (automagic-analysis left {:show         (keyword show)
                                            :query-filter nil
                                            :comparison?  true})]
    (comparison-dashboard dashboard left right {:left {:cell-query (decode-base64-json cell-query)}})))

#_{:clj-kondo/ignore [:deprecated-var]}
(api/defendpoint-schema GET "/:entity/:entity-id-or-query/cell/:cell-query/rule/:prefix/:rule/compare/:comparison-entity/:comparison-entity-id-or-query"
  "Return an automagic comparison dashboard for cell in automagic dashboard for entity `entity`
   with id `id` defined by query `cell-querry` using rule `rule`; compared with entity
   `comparison-entity` with id `comparison-entity-id-or-query.`."
  [entity entity-id-or-query cell-query prefix rule show comparison-entity comparison-entity-id-or-query]
  {entity            Entity
   show              Show
   prefix            Prefix
   rule              Rule
   cell-query        Base64EncodedJSON
   comparison-entity ComparisonEntity}
  (let [left      (->entity entity entity-id-or-query)
        right     (->entity comparison-entity comparison-entity-id-or-query)
        dashboard (automagic-analysis left {:show         (keyword show)
                                            :rule         ["table" prefix rule]
                                            :query-filter nil})]
    (comparison-dashboard dashboard left right {:left {:cell-query (decode-base64-json cell-query)}})))

(api/define-routes)
