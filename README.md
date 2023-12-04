# defacto

A lightweight, highly customizable state store for clojure(script).

## Usage

```clojure
(require '[defacto.core :as defacto])

;; make some command handlers
(defmethod defacto/command-handler :stuff/create!
  [{::defacto/keys [store] :as _ctx-map} _db-value [_ command-arg :as _command] emit-cb]
  (do-stuff! ctx-map)
  (defacto/dispatch! store [:another/command!])
  (emit-cb [:stuff/created command-arg]))

;; make some event handlers
(defmethod defacto/event-handler :stuff/created
  [db [_ value :as _event]]
  (assoc db :stuff/value value))

;; make some subscription handlers
(defmethod defacto/query :stuff/stuff?
  [db [_ default]]
  (:stuff/value db default))


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
[:some.domain/something? {...}] ;; `queries` are nouns ending with a `?`
```

## Use with Reagent

I love [reagent](https://github.com/reagent-project/reagent), and I use it for all my cljs projects. Making a
reactive `reagent` store with `defacto` is super easy!

```clojure
(defacto/->DefactoStore ctx-map (atom initial-db) reagent.core/atom)
```

## Why?

Good question. [re-frame](https://github.com/day8/re-frame) is awesome, but it's too heavy-weight for me.
Also, I prefer to be able to make multiple, isolated stores.
