# defacto

The `defacto` state store library. A [lightweight](https://github.com/skuttleman/defacto/blob/master/deps.edn),
highly customizable state store for clojure(script).

## Usage

```clojure
(require '[defacto.core :as defacto])
(require '[clojure.core.async :as async])

;; make some command handlers
(defmethod defacto/command-handler :stuff/create!
  [{::defacto/keys [store] :as ctx-map} [_ command-arg :as _command] emit-cb]
  (do-stuff! ctx-map command-arg)
  ;; command handlers can do work synchronously and/or asynchronously
  (async/go
    (let [result (async/<! (do-more-stuff! ctx-map command-arg))]
      (if (success? result)
        (emit-cb [:stuff/created (async/<! (do-more-more-stuff! ctx-map command-arg))])
        (defacto/dispatch! store [:another/command! result])))))

;; make some event handlers
(defmethod defacto/event-reducer :stuff/created
  [db [_ value :as _event]]
  (assoc db :stuff/value value))

;; make some subscription handlers
(defmethod defacto/query-responder :stuff/stuff
  [db [_ default]]
  (or (:stuff/value db) default))


;; make a store
(def my-store (defacto/create {:some :ctx} {:stuff/value nil}))

;; make a subscription
(def subscription (defacto/subscribe my-store [:stuff/stuff 3]))
(deref subscription)
;; returns default value
;; => 3

;; dispatch a command
(defacto/dispatch! my-store [:stuff/create! 7])
;; value is updated in store
(deref subscription)
;; => 7


;; emit an event directly
(defacto/emit! my-store [:some/event {...}])
```

The design is vaguely `CQS` (just like most state stores). As such, consider adopting a convention to help organize
which `keywords` you use for `commands`, `events`, or `queries`. Here is a convention I like:

```clojure
[:some.domain/do-something! {...}] ;; `commands` are present-tense verbs ending with a `!`
[:some.domain/something-happened {...}] ;; `events` are past-tense verbs
[:some.domain/thing {...}] ;; `queries` are nouns
```

## Use with Reagent

I love [reagent](https://github.com/reagent-project/reagent), and I use it for all my cljs UIs. Making a
reactive `reagent` store with `defacto` is super easy!

```clojure
(ns killer-app.core
  (:require
    [cljs.core.async :as async]
    [cljs-http.client :as http]
    [defacto.core :as defacto]
    [reagent.core :as r]
    [reagent.dom :as rdom]))

(def component [store]
  (r/with-let [sub (defacto/subscribe [::page-data 123])]
    (let [{:keys [status data error]} @sub]
      [:div.my-app
       [:h1 "Hello, app!"]
       [:button {:on-click #(defacto/dispatch! store [::fetch-data! 123])}
        "fetch data"]
       [:div
        (case status
          :ok data
          :bad error
          "nothing yet")]])))

(defn app-root []
  (r/with-let [store (defacto/create {:http-fn http/request} {:init :db} r/atom)]
                                                                      ;; using [[r/atom]] gets you a
                                                                      ;; **reactive subscriptions**
    [component store]))

(rdom/render [app-root] (.getElementById js/document "root"))

(defmethod defacto/command-handler ::fetch-data!
  [{::defacto/keys [store] :keys [http-fn]} [ id] emit-cb]
  (async/go
    (let [result (async/<! (http-fn {...}))
          ;; deref-ing the store is NOT reactive and can be used inside command handlers
          current-db @store
          ;; query the db directly instead of using subscriptions
          page-data (defacto/query-responder current-db [::page-data])]
      (do-something-with page-data)
      (if (= 200 (:status result))
        (emit-cb [::fetch-succeeded {:id id :data (:body result)}])
        (emit-cb [::fetch-failed {:id id :error (:body result)}])))))

(defmethod defacto/event-reducer ::fetch-succeeded
  [db [_ {:keys [id data]}]]
  (assoc-in db [:my-data id] {:status :ok :data data}))

(defmethod defacto/event-reducer ::fetch-failed
  [db [_ {:keys [id error]}]]
  (assoc-in db [:my-data id] {:status :bad :data error}))

(defmethod defacto/query-responder ::page-data
  [db [_ id]]
  (get-in db [:my-data id]))
```

## Why?

Good question. [re-frame](https://github.com/day8/re-frame) is awesome, but it's too heavy-weight for my purposes.
Sometimes I just want to build things out of tiny, composable pieces.
