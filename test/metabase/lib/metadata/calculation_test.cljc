(ns metabase.lib.metadata.calculation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.test-metadata :as meta]
   [metabase.lib.test-util :as lib.tu]
   [metabase.util :as u]
   #?@(:cljs ([metabase.test-runner.assert-exprs.approximately-equal]))))

#?(:cljs (comment metabase.test-runner.assert-exprs.approximately-equal/keep-me))

(deftest ^:parallel calculate-names-even-without-metadata-test
  (testing "Even if metadata is missing, we should still be able to calculate reasonable display names"
    (doseq [query [(-> lib.tu/venues-query
                       (lib/order-by (meta/field-metadata :orders :total)))
                   (-> lib.tu/venues-query
                       (lib/order-by [:field
                                      {:lib/uuid  (str (random-uuid))
                                       :base-type :type/Text}
                                      "TOTAL"]))]]
      (testing (str "\nquery =\n" (u/pprint-to-str query))
        (is (= "Venues, Sorted by Total ascending"
               (lib.metadata.calculation/suggested-name query)))))))

(deftest ^:parallel long-display-name-test
  (let [query lib.tu/venues-query
        results (->> query
                     lib.metadata.calculation/visible-columns
                     (map (comp :long-display-name #(lib/display-info query 0 %))))]
    (is (= ["ID" "Name" "Category ID" "Latitude" "Longitude" "Price" "Category → ID" "Category → Name"]
           results)))

  (let [query (lib/query meta/metadata-provider (meta/table-metadata :orders))
        results (->> query
                     lib.metadata.calculation/visible-columns
                     (map (comp :long-display-name #(lib/display-info query 0 %))))]
    (is (= ["ID"
            "User ID"
            "Product ID"
            "Subtotal"
            "Tax"
            "Total"
            "Discount"
            "Created At"
            "Quantity"
            "User → ID"
            "User → Address"
            "User → Email"
            "User → Password"
            "User → Name"
            "User → City"
            "User → Longitude"
            "User → State"
            "User → Source"
            "User → Birth Date"
            "User → Zip"
            "User → Latitude"
            "User → Created At"
            "Product → ID"
            "Product → Ean"
            "Product → Title"
            "Product → Category"
            "Product → Vendor"
            "Product → Price"
            "Product → Rating"
            "Product → Created At"]
           results))))

(deftest ^:parallel visible-columns-test
  (testing "Include all visible columns, not just projected ones (#31233)"
    (is (= ["ID"
            "NAME"
            "CATEGORY_ID"
            "LATITUDE"
            "LONGITUDE"
            "PRICE"
            "Categories__ID"            ; this column is not projected, but should still be returned.
            "Categories__NAME"]
           (map :lib/desired-column-alias
                (-> lib.tu/venues-query
                    (lib/join (-> (lib/join-clause
                                   (meta/table-metadata :categories)
                                   [(lib/=
                                     (meta/field-metadata :venues :category-id)
                                     (lib/with-join-alias (meta/field-metadata :categories :id) "Categories"))])
                                  (lib/with-join-fields [(lib/with-join-alias (meta/field-metadata :categories :name) "Categories")])))
                    lib.metadata.calculation/visible-columns)))))
  (testing "nil has no visible columns (#31366)"
    (is (empty? (-> lib.tu/venues-query
                    (lib.metadata.calculation/visible-columns nil)))))

  (testing "multiple aggregations"
    (lib.metadata.calculation/visible-columns
      (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
          (lib/aggregate (lib/count))
          (lib/aggregate (lib/sum (meta/field-metadata :orders :quantity)))))))

(deftest ^:parallel source-cards-test
  (testing "with :source-card"
    (let [query {:lib/type     :mbql/query
                 :lib/metadata lib.tu/metadata-provider-with-mock-cards
                 :database     (meta/id)
                 :stages       [{:lib/type :mbql.stage/mbql
                                 :source-card (:id (lib.tu/mock-cards :orders))}]}
          own-fields (for [field (lib.metadata/fields lib.tu/metadata-provider-with-mock-cards (meta/id :orders))]
                       (-> field
                           (assoc :lib/source :source/card)
                           (dissoc :id :table-id)))]
      (testing "implicitly joinable columns"
        (testing "are included by visible-columns"
          (is (=? (->> (concat own-fields
                               (for [field (lib.metadata/fields lib.tu/metadata-provider-with-mock-cards (meta/id :people))]
                                 (assoc field :lib/source :source/implicitly-joinable))
                               (for [field (lib.metadata/fields lib.tu/metadata-provider-with-mock-cards (meta/id :products))]
                                 (assoc field :lib/source :source/implicitly-joinable)))
                       (sort-by (juxt :name :id)))
                  (sort-by (juxt :name :id) (lib.metadata.calculation/visible-columns query)))))
        (testing "are not included by returned-columns"
          (is (=? (sort-by (juxt :name :id) own-fields)
                  (sort-by (juxt :name :id) (lib.metadata.calculation/returned-columns query)))))))))
