# defacto

A lightweight, highly customizable state store for clojure(script).

## Usage

```clojure
(require '[defacto.core :as defacto])
(require '[clojure.core.async :as async])

;; make some command handlers
(defmethod defacto/command-handler :stuff/create!
  [{::defacto/keys [store] :as _ctx-map} _db-value [_ command-arg :as _command] emit-cb]
  (async/go
    (do-stuff! ctx-map)
    (defacto/dispatch! store [:another/command!])
    (do-more-stuff! ctx-map)
    (emit-cb [:stuff/created command-arg])))

;; make some event handlers
(defmethod defacto/event-handler :stuff/created
  [db [_ value :as _event]]
  (assoc db :stuff/value value))

;; make some subscription handlers
(defmethod defacto/query :stuff/stuff?
  [db [_ default]]
  (or (:stuff/value db) default))


;; make a store
(def my-store (defacto/create {:some :ctx} {:stuff/value nil}))

;; make a subscription
(def subscription (defacto/subscribe my-store [:stuff/stuff? 3]))
(deref subscription)
;; returns default value
;; => 3

;; dispatch a command
(dispatch! my-store [:stuff/create! 7])
;; value is updated in store
(deref subscription)
;; => 7


;; dispatch an event synchronously or asynchronously
(dispatch my-store [::defacto/sync! [:some/event {...}]])
(dispatch my-store [::defacto/async! [:some/event {...}]])
```

The design is vaguely `CQS` (just like most state stores). As such, consider adopting a convention to help organize
which `keywords` you use for `commands`, `events`, or `queries`. Here is a convention I like:

```clojure
[:some.domain/do-something! {...}] ;; `commands` are present-tense verbs ending with a `!`
[:some.domain/something-happened {...}] ;; `events` are past-tense verbs
[:some.domain/thing? {...}] ;; `queries` are nouns ending with a `?`
```

## Use with Reagent

I love [reagent](https://github.com/reagent-project/reagent), and I use it for all my cljs projects. Making a
reactive `reagent` store with `defacto` is super easy!

```clojure
(ns killer-app.core
  (:require
    [cljs.core.async :as async]
    [cljs-http.client :as http]
    [defacto.core :as defacto]
    [reagent.core :as r]
    [reagent.dom :as rdom]))

(def component []
  (r/with-let [store (defacto/create {:http-fn http/request} {:init :db} r/atom)
               sub (defacto/subscribe [::page-data? 123])]
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

(rdom/render [component] (.getElementById js/document "root"))

(defmethod defacto/command-handler ::fetch-data!
  [{:keys [http-fn]} [ id] emit-cb]
  (async/go
    (let [result (async/<! (http-fn {...}))]
      (if (= 200 (:status result))
        (emit-cb [::fetch-succeeded {:id id :data (:body result)}])
        (emit-cb [::fetch-failed {:id id :error (:body result)}])))))

(defmethod defacto/event-handler ::fetch-succeeded
  [db [_ {:keys [id data]}]]
  (assoc-in db [:my-data id] {:status :ok :data data}))

(defmethod defacto/event-handler ::fetch-failed
  [db [_ {:keys [id error]}]]
  (assoc-in db [:my-data id] {:status :bad :data error}))

(defmethod defacto/query ::page-data?
  [db [_ id]]
  (get-in db [:my-data id]))
```

## Why?

Good question. [re-frame](https://github.com/day8/re-frame) is awesome, but it's too heavy-weight for me. I prefer
building things out of smaller things.
