;; Copyright (c) 2020 DINUM, Bastien Guerry <bastien.guerry@data.gouv.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE

(ns orgas
  (:require  [cheshire.core :as json]
             [clojure.data.csv :as csv]
             [babashka.curl :as curl]
             [clojure.set :as set]
             [clojure.string :as s]))

(defonce orgas-url
  "https://raw.githubusercontent.com/etalab/data-codes-sources-fr/master/data/organisations/json/all.json")

(defonce orgas-esr-url
  "https://raw.githubusercontent.com/DISIC/politique-de-contribution-open-source/master/comptes-organismes-publics-esr")

(defonce annuaire-url ;; returns a csv
  "https://static.data.gouv.fr/resources/organisations-de-codegouvfr/20191011-110549/lannuaire.csv")

(defonce orgas-floss-policy-url  ;; returns a csv
  "https://raw.githubusercontent.com/DISIC/politique-de-contribution-open-source/master/comptes-organismes-avec-politique-de-publication-floss.csv")

;; Ignore these keywords
;; :private :default_branch :language :id :checked :owner :full_name
(def orgas-mapping
  "Mapping from groups/organizations keywords to local short versions."
  {:description        :d
   :adresse            :a
   :email              :e
   :nom                :n
   :plateforme         :p
   :site_web           :h
   :est_verifiee       :v?
   :login              :l
   :date_creation      :c
   :nombre_repertoires :r
   :organisation_url   :o
   :avatar_url         :au})

;; Utility functions

(defn- rows->maps [csv]
  (let [headers (map keyword (first csv))
        rows    (rest csv)]
    (map #(zipmap headers %) rows)))

(defn csv-url-to-map [url]
  (rows->maps (csv/read-csv (:body (curl/get url)))))

;; Core functions

(defn add-data []
  (let [esr-orgas (into #{}
                        (s/split-lines
                         (try (:body (curl/get orgas-esr-url))
                              (catch Exception e
                                (println (.getMessage e))))))
        floss-pol (apply merge
                         (map #(let [{:keys [organisation url-politique-floss]} %]
                                 {organisation url-politique-floss})
                              (try (csv-url-to-map orgas-floss-policy-url)
                                   (catch Exception e
                                     (println (.getMessage e))))))
        annuaire  (apply merge
                         (map #(let [{:keys [github lannuaire]} %]
                                 {(keyword github) lannuaire})
                              (try (csv-url-to-map annuaire-url)
                                   (catch Exception e
                                     (println (.getMessage e))))))
        deps      (json/parse-string
                   (try (slurp "deps-orgas.json")
                        (catch Exception e
                          (println (.getMessage e)))))]
    (comp
     ;; Remap keywords
     (map #(set/rename-keys % orgas-mapping))
     ;; Only keep organizations with repositories
     (filter #(pos? (:r %)))
     ;; Add information from `orgas-floss-policy-url`
     (map #(assoc % :fp (get floss-pol (:o %))))
     ;; Add information from `orgas-esr-url`
     (map #(assoc % :esr? (contains?
                           esr-orgas
                           ;; :o contains the true group
                           ;; URL, whereas our source file
                           ;; `orgas-esr-url` does not, so
                           ;; replace /groups/ with /:
                           (s/replace (:o %) "/groups/" "/"))))
     ;; Add information from `annuaire-url`.
     (map #(assoc % :an ((keyword (:l %)) annuaire)))
     ;; Add orga deps number
     (map #(if-let [d (not-empty (get deps (str [(:l %) (:p %)])))]
             (assoc % :dp (count d))
             %)))))

(defn init
  "Generate orgas.json from `orgas-url`."
  []
  (when-let [orgas (:body (try (curl/get orgas-url)
                               (catch Exception e
                                 (println (.getMessage e)))))]
    (spit "orgas-raw.json" orgas)
    (json/parse-string orgas true)))
