(ns exploud.http
  (:require [clj-http.client :as http]))

(defn default-params [& [overrides]]
  (merge {:throw-exceptions false
          :conn-timeout 5000
          :socket-timeout 15000}
         overrides))

(defn simple-get [url & [params]]
  (http/get url (default-params params)))

(defn simple-post [url & [params]]
  (http/post url (default-params params)))
