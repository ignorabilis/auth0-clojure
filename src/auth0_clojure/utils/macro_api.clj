(ns auth0-clojure.utils.macro-api
  (:require [auth0-clojure.utils.requests :as requests]))

;; TLDR: forget about this one for now

;; A data approach here would clearly be better - stuff will be compositional,
;; meaning that it's easier to juggle maps instead of functions (which get generated by a macro)
;; try to make a quick data implementation

;; TODO - implement query params
(def api-description-test
  {:get-connection    {:description "Gets a connection"
                       ;; TODO-param-val - implement parameters validation using a hash map
                       :path        ["connections" :id]
                       :method      :get}
   :update-connection {:description "Updates a connection"
                       :path        ["connections" :id]
                       :method      :patch
                       ;; TODO - implement body validation
                       ;:body-validation nil
                       :headers     {:custom "random"}}})

(defn path->fn-args
  "Converts the path provided to a destructuring map. Each keyword is considered
  a parameter.
  Example:
  [\"connections\" :id] -> {id :id :as params}"
  [path]
  (let [args (->> path
                  ;; TODO-param-val - implement parameters validation using a hash map
                  (filter #(or (keyword? %) #_(map? %)))
                  (map #(identity [(symbol %) %]))
                  (into {}))
        args (assoc args :as 'params)]
    args))

(defn runtime-path
  "Matches params keys provided with their actual values at runtime."
  [path params]
  (map
    #(if-let [param (get params %)]
       ;; TODO-param-val - support hash map & validation here as well
       param
       %)
    path))

(defn create-api-call
  "Creates an API function based on an EDN API definition."
  [[name {:keys [:description :path] :as api-def}]]
  (let []
    `(defn ~(symbol name)
       ~description
       [~(path->fn-args path)]
       (requests/auth0-mgmt-request
         ;; TODO - extract this config here
         {:auth0/default-domain ""
          :auth0/custom-domain  ""
          :auth0/mgmt-access-token "bugi"}
         (runtime-path ~path ~'params)
         (select-keys ~api-def [:method :body :headers])))))

(defmacro implement-api
  "Given an EDN API definition generates functions
  which receive http method, parameters and other custom data
  and return an EDN request, which in turn can be used to invoke
  an endpoint."
  [api-description]
  (let [api-description (eval api-description)]
    `(do
       ~@(map create-api-call api-description))))

(comment
  "end result of the get connection" ->
  {:url "https://auth.workframe.com/api/v2/connections/trytka",
   :method :get,
   :content-type :json,
   :accept :json,
   :as :auth0-edn,
   :throw-exceptions false,
   :headers {"Authorization" "Bearer bugi"}}
  {:url "https://auth.workframe.com/api/v2/connections/trytka",
   :method :get,
   :content-type :json,
   :accept :json,
   :as :auth0-edn,
   :throw-exceptions false,
   :headers {"Authorization" "Bearer bugi"}})