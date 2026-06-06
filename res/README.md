# defacto-resources

A module for `defacto` that generically handles "asynchronous" resources.

```clojure
;; deps.edn
{:deps {skuttleman/defacto-res {:git/url   "https://github.com/skuttleman/defacto"
                                :git/sha   "{SHA_OF_HEAD}"
                                :deps/root "res"}}}
```

```clojure
(ns killer-app.core
  (:require
    [defacto.core :as defacto]
    [defacto.resources.core :as res]))

;; define your resource spec by returning a map by including any of the following
(defmethod res/->request-spec ::fetch-thing
  [_ input]
  {:pre-events   [[::fetch-started]]
   :params       {:request-method :get
                  :url            (str "http://www.example.com/things/" (:id input))}
   :err-commands [[::toast!]]
   :ok-events    ...})

;; define a request-fn
(defn my-request-fn [resource-type params]
  ;; returns a vector tuple or a core.async channel
  (async/go
    ;; does whatever, http prolly
    ...
    ;; succeeds with a vector tuple
    [::res/ok {:some :data}] ;; if it isn't `::res/ok`, it's `::res/err`
    ;; or fails with a vector tuple
    [::res/err {:some :error}]))


;; resource key
(def resource-key [::fetch-thing ::numero-uno])

;; create your store your request handler
(def store (defacto/create (res/with-ctx {:some :ctx-map} my-request-fn) {}))
(def sub (defacto/subscribe store [::res/?:resource resource-key]))

;; submit the resource
(defacto/dispatch! store [::res/submit! resource-key {:id 123}])
(res/requesting? @sub) ;; => true
... after the request finishes
(res/success? @sub) ;; true (one would hope)
(res/payload @sub) ;; => {...}
```

## What's a resource?

A `resource` is defined by extending [[defacto.resources.core/->request-spec]] with your `resource-type` which
you can use to create and reference resources in the system.

```clojure
(defmethod defacto.resources.core/->request-spec ::resource-type
  [resource-key input] ;; resource-key is a vector beginning with the `resource-type`
  {:params {...}
   ...})
```

Your spec can return any of the following keys

- `:params` - a NON-`nil` argument to request the resource. If this key is `nil`, the resource will not be requested.
- `:pre-events`, `:pre-commands` - optional sequences of events/commands to be emitted/dispatched before the request
  is submitted. These occur even if `:params` is `nil`
- `:ok-events`, `:ok-commands` - optional sequences of events/commands to emitted/dispatched after the request succeeds.
  These events/commands should be express "callback" style with the final argument to be the success result `conj`'ed on
  to the event/command vector.
- `:err-events`, `:err-commands` - optional sequences of events/commands to emitted/dispatched after the request fails.
  These events/commands should be express "callback" style with the final argument to be the error result `conj`'ed on
  to the event/command vector.
- `:->ok` - optionally transform the ok response payload before it is `conj`'ed onto ok events/commands
- `:->err` - optionally transform the error response payload before it is `conj`'ed onto err events/commands

## Commands

This module exposes the following `commands`.

### [::res/do! resource-key params]

Dispatches a request using the resource's `->request-spec`. Unlike `::res/submit!`, no resource state management is performed automatically — resource state transitions are governed entirely by the events and commands defined in the `->request-spec`.

```clojure
(defacto/dispatch! store [::res/do! [::resource-type] {:a 1}])
```

### [::res/submit! resource-key params]

This submits a resource with the provided params.

```clojure
(defacto/dispatch! store [::res/submit! [::resource-type] {:a 1}])
```

### [::res/ensure! resource-key params]

This submits a resource if it is currently in the `:init` state. Accepts an optional `::res/ttl` param (milliseconds); if provided, the resource will be resubmitted when it was last fetched longer ago than the TTL.

```clojure
(defacto/dispatch! store [::res/ensure! [::resource-type] {:a 1}])
;; resubmits if the resource was last fetched more than 30 seconds ago
(defacto/dispatch! store [::res/ensure! [::resource-type] {::res/ttl 30000}])
```

### [::res/poll! milliseconds resource-key params]

Continuously submits a resource in intervals of `milliseconds`.

```clojure
;; sends a request now, and after every 2 seconds forever
(defacto/dispatch! store [::res/poll! 2000 [::resource-type] {:a 1}])
;; destroy the resource to stop the polling
(defacto/emit! store [::res/destroyed [::resource-type]])
```

### [::res/resubmit! resource-key params]

Resubmits a resource, transitioning it to `:requesting` while retaining the current state as a fallback. On success, transitions to `:success`. On failure, reverts to the previous state via `::res/reverted`.

```clojure
(defacto/dispatch! store [::res/resubmit! [::resource-type] {:a 1}])
```

### [::res/delay! milliseconds command]

Executes a command after `milliseconds` have expired.

```clojure
(defacto/dispatch! store [::res/delay! 123 [::any-command! {:a 1}]])
```

## Queries

This module exposes the following `queries`.

### [::res/?:resources]

Returns a sequence of all resources.

```clojure
@(defacto/subscribe store [::res/?:resources])
;; returns `nil` for undefined resources
```

### [::res/?:resource resource-key]

Retrieves the current state of a resource.

```clojure
@(defacto/subscribe store [::res/?:resource [::some-key 123]])
;; returns `nil` for undefined resources
```

## Events

This module exposes the following `events`.

### [::res/submitted resource-key request-params]

Transitions the resource from any state to `:requesting`. **Not intended to be used directly**

### [::res/resubmitted resource-key]

Transitions the resource to `:requesting` while saving the current state for potential reversion. **Not intended to be used directly**

### [::res/succeeded resource-key data]

Transitions the resource from a `:requesting` state to a `:success` state. **Not intended to be used directly**

### [::res/reverted resource-key]

Reverts the resource to its previous state after a failed `::res/resubmit!`. **Not intended to be used directly**

### [::res/failed resource-key error]

Transitions the resource from a `:requesting` state to an `:error` state. **Not intended to be used directly**

### [::res/destroyed resource-key]

Destroys a resource.

```clojure
(defacto/emit! store [::res/destroyed [::resource-type]])
```

### [::res/swapped resource-key data]

Swaps the payload of a resource in `:success` state without making a new request.

```clojure
(defacto/emit! store [::res/swapped [::resource-type] {:new :payload}])
```
