(ns auth0-clojure.api.authentication
  (:require [auth0-clojure.utils.edn :as edn]
            [auth0-clojure.utils.json :as json]
            [auth0-clojure.utils.common :as common]
            [clj-http.client :as client]
            [org.bovinegenius.exploding-fish :as uri]))

(def global-config
  (atom {}))

(defn set-config! [new-config]
  (reset! global-config new-config))

;; TODO - these could be exposed too
;; withScope should work with plain string like
;; "openid email profile"
;; or with a set or vector of strings/keywords, like this:
(comment
  [:auth0.scope/openid :auth0.scope/email]
  #{:auth0.scope/openid :auth0.scope/profile}
  ;alternatives
  #{:openid :email})
;; These then get converted to a set, then to string
;; TODO - scope validation (if string convert to set & validate)
;; Can add spec so that apart from scope also redirect uri is valid, state is
;; string, etc.

;; TODO - do I need ^String, ^PersistentHashMap, etc.?

(def https-scheme "https")

;; TODO - memoize base-url based on default & custom domains
(defn base-url
  ([]
   (base-url @global-config))
  ([{:keys [:auth0/default-domain
            :auth0/custom-domain]}]
   (uri/map->uri {:scheme https-scheme
                  :host   (or custom-domain default-domain)})))

;; Generate query param values like `federated` with an equal sign;
;; it's the safe bet since Auth0 is doing the same
(defn parse-value [k v]
  (case k
    :auth0/federated (when v "")
    v))

(def raw-param-ks
  #{:auth0/redirect-uri})

(defn param-key->param-fn
  "Some query parameters should be raw, depending on the key."
  [param-key]
  (if (contains? raw-param-ks param-key)
    uri/param-raw
    uri/param))

(defn build-url-params-base [uri params-map]
  (reduce
    (fn [auth-url [k v]]
      (let [parsed-val (parse-value k v)
            param-fn   (param-key->param-fn k)]
        ;; remove any nil values, otherwise they get added to query params without an equal sign
        ;; for example {:federated nil} -> ... &federated&some_other=1 ...
        (if (nil? parsed-val)
          auth-url
          (param-fn
            auth-url
            (edn/kw->str-val k)
            parsed-val))))
    uri
    params-map))

(defn build-url-params [uri params-map]
  ;; TODO - adding raw params in the end is a workaround for that issue:
  ;; https://github.com/wtetzner/exploding-fish/issues/26
  ;; revert once it is fixed
  (let [raw-params-map (select-keys params-map raw-param-ks)
        params-map     (apply dissoc params-map raw-param-ks)
        params-uri     (build-url-params-base uri params-map)
        raw-params-uri (build-url-params-base params-uri raw-params-map)]
    raw-params-uri))

;; TODO - redirect-uri is a MUST
;; TODO - check if the same is valid for scope: openid
;; TODO - spec for valid keys here: scope, state, audience, connection, response-type
(defn authorize-url
  "Must have param: redirect-uri
  Valid params: connection audience scope state response-type"
  ([params]
   (authorize-url @global-config params))
  ([config params]
   (let [base-url       (base-url config)
         auth-url       (uri/path base-url "/authorize")
         param-auth-url (build-url-params
                          auth-url
                          (merge
                            params
                            (select-keys config [:auth0/client-id])))
         string-url     (-> param-auth-url uri/uri->map uri/map->string)]
     string-url)))

;; TODO - return-to is a MUST
;; TODO - is setClientId from the Java version needed?
(defn logout-url
  "Must have param: return-to
  Valid params: federated"
  ([params]
   (logout-url @global-config params))
  ([config params]
   (let [base-url         (base-url config)
         logout-url       (uri/path base-url "/v2/logout")
         param-logout-url (build-url-params
                            logout-url
                            (merge
                              params
                              (select-keys config [:auth0/client-id])))
         string-url       (-> param-logout-url uri/uri->map uri/map->string)]
     string-url)))

(comment

  (authorize-url
    {:auth0/response-type "code"
     :auth0/scope         "openid profile"
     :auth0/redirect-uri  "http://localhost:1111/login-user"})

  (logout-url
    {:auth0/return-to "http://localhost:1111/login"
     :auth0/federated true}))

;; TODO - refactor in utils, urls, requests

;; SAML
;; the only param is connection and it is optional
(defn accept-saml-url
  ([params]
   (accept-saml-url @global-config params))
  ([{:as   config
     :keys [:auth0/client-id]}
    params]
   (let [base-url       (base-url config)
         saml-url       (uri/path base-url (str "/samlp/" client-id))
         param-saml-url (build-url-params saml-url params)
         string-url     (-> param-saml-url uri/uri->map uri/map->string)]
     string-url)))

(defn saml-metadata-url
  ([]
   (saml-metadata-url @global-config))
  ([{:as   config
     :keys [:auth0/client-id]}]
   (let [base-url   (base-url config)
         saml-url   (uri/path base-url (str "/samlp/metadata/" client-id))
         string-url (-> saml-url uri/uri->map uri/map->string)]
     string-url)))

;; providing a connection is optional;
;; no connection -> SP initiated url
;; connection -> IdP initiated url
(defn sp-idp-init-flow-url
  ([params]
   (sp-idp-init-flow-url @global-config params))
  ([config params]
   (let [base-url       (base-url config)
         saml-url       (uri/path base-url "/login/callback")
         param-saml-url (build-url-params saml-url params)
         string-url     (-> param-saml-url uri/uri->map uri/map->string)]
     string-url)))

(comment

  ;; get default auth0 login screen
  (accept-saml-url
    {})

  ;; get the login screen for the respective connection
  (accept-saml-url
    {:auth0/connection "<samlp-connection-name>"})

  (saml-metadata-url))

;; requests start from here

(def authorization-header "Authorization")
(def bearer "Bearer ")

(def oauth-ks
  #{:auth0/grant-type})

(defn oauth-vals-edn->json [body]
  (let [edn-vals  (select-keys body oauth-ks)
        json-vals (into {} (for [[k v] edn-vals] [k (json/kw->str-val v)]))
        body      (merge body json-vals)]
    body))

(defmethod client/coerce-response-body :auth0-edn [_ resp]
  (json/coerce-responce-body-to-auth0-edn resp))

;; TODO - move this in a different ns
(defn auth0-request [config path options]
  (let [base-url      (base-url
                        (select-keys
                          config
                          [:auth0/default-domain
                           :auth0/custom-domain]))
        user-info-url (uri/path base-url path)
        string-url    (-> user-info-url uri/uri->map uri/map->string)]
    (merge
      ;; TODO - getting EDN is cool, but in some cases JSON might be preferable - make this configurable
      {:url              string-url
       :method           :get
       :content-type     :json
       :accept           :json
       :as               :auth0-edn
       :throw-exceptions false}
      (common/edit-if
        options
        :body
        (fn [body]
          (-> body
              oauth-vals-edn->json
              json/edn->json))))))

;; TODO - spec the map later
(defn oauth-token
  ([opts]
   (oauth-token @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id :auth0/client-secret]}
    opts]
   (let [request (auth0-request
                   config
                   "/oauth/token"
                   {:method :post
                    :body   (merge
                              {:auth0/client-id     client-id
                               :auth0/client-secret client-secret}
                              opts)})]
     (client/request request))))

(comment
  ;; this is the login url used for testing - only openid scope
  "https://ignorabilis.auth0.com/authorize?response_type=code&scope=openid&client_id=wWiPfXbLs3OUbR74JpXXhF9jrWi3Sgd8&redirect_uri=http://localhost:1111/user"
  ;; this is the login url used for testing - openid profile email
  "https://ignorabilis.auth0.com/authorize?response_type=code&scope=openid+profile+email&client_id=wWiPfXbLs3OUbR74JpXXhF9jrWi3Sgd8&redirect_uri=http://localhost:1111/user"

  ;; this is the req for getting an access-token; just change the code
  (oauth-token
    {:auth0/code         "CODE_HERE"
     :auth0/redirect-uri "http://localhost:1111/"
     :auth0/grant-type   :auth0.grant-type/authorization-code}))

;; only :auth0/token is required
(defn oauth-revoke
  ([opts]
   (oauth-revoke @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id
            :auth0/client-secret]}
    opts]
   (let [request (auth0-request
                   config
                   "/oauth/revoke"
                   {:method :post
                    :body   (merge
                              {:auth0/client-id     client-id
                               :auth0/client-secret client-secret}
                              opts)})]
     (client/request request))))

;; TODO - access-token is a MUST - needs spec
(defn user-info
  ([access-token]
   (user-info @global-config access-token))
  ([config access-token]
   (let [request (auth0-request
                   config
                   "/userinfo"
                   {:headers {authorization-header (str bearer access-token)}})]
     (client/request request))))

(defn passwordless-start
  ([opts]
   (passwordless-start @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id]}
    opts]
   (let [request (auth0-request
                   config
                   "/passwordless/start"
                   {:method :post
                    :body   (merge
                              {:auth0/client-id client-id}
                              opts)})]
     (client/request request))))

(comment

  (passwordless-start
    {:auth0/connection "email"
     :auth0/email      "irina.yaroslavova@ignorabilis.com"
     ;:auth0/send       "link"           ;; can be link/code
     ;:auth0/authParams {:auth0/scope "openid"}
     }))

(defn passwordless-verify
  ([opts]
   (passwordless-verify @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id]}
    opts]
   (let [request (auth0-request
                   config
                   "/passwordless/verify"
                   {:method :post
                    :body   (merge
                              {:auth0/client-id client-id}
                              opts)})]
     (client/request request))))

(defn sign-up
  ([opts]
   (sign-up @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id]}
    opts]
   (let [request (auth0-request
                   config
                   "/dbconnections/signup"
                   {:method :post
                    :body   (merge
                              {:auth0/client-id client-id}
                              opts)})]
     (client/request request))))

(comment

  ;; minimal
  (sign-up
    {:auth0/email      "irina@lumberdev.nyc"
     :auth0/password   "123"
     :auth0/connection "Username-Password-Authentication"})

  ;; all
  (sign-up
    {:auth0/email         "irina@lumberdev.nyc"
     :auth0/password      "123"
     :auth0/connection    "Username-Password-Authentication"
     :auth0/username      "ignorabilis" ;; ignored if not required by DB
     :auth0/given-name    "Irina"
     :auth0/family-name   "Stefanova"
     :auth0/name          "Irina Yaroslavova Stefanova"
     :auth0/nickname      "Iri"
     :auth0/picture       "https://image.shutterstock.com/image-vector/woman-profile-picture-vector-260nw-438753232.jpg"
     :auth0/user-metadata {:some-random "metadata"}})
  )

;; TODO - error responses return json; take care of the inconsistent API and convert those responses to json manually
(defn change-password
  ([opts]
   (change-password @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id]}
    opts]
   (let [request (auth0-request
                   config
                   "/dbconnections/change_password"
                   {:method :post
                    :accept :text
                    :as     :text
                    :body   (merge
                              {:auth0/client-id client-id}
                              opts)})]
     (client/request request))))

(comment
  (change-password
    {:auth0/email      "irina@lumberdev.nyc"
     :auth0/connection "Username-Password-Authentication"}))

(defn dynamically-register-client
  ([opts]
   (dynamically-register-client @global-config opts))
  ([{:as   config
     :keys [:auth0/client-id]}
    {:as   opts
     :keys [:auth0/redirect-uris]}]
   (let [request (auth0-request
                   config
                   "/oidc/register"
                   {:method :post
                    :body   (merge
                              {:auth0/redirect-uris (or redirect-uris [])}
                              opts)})]
     (client/request request))))

(comment
  (dynamically-register-client
    {:auth0/client-name "New client"}))