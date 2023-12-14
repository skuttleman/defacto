# defacto-resources

A module for `defacto` that generically handles "asynchronous" resources.

```clojure
(ns killer-app.core
  (:require
    [defacto.core :as defacto]
    [defacto.resources.core :as res]))

;; define your resource spec by returning a map by including any of the following
(defmethod res/->resource-spec ::fetch-thing
  [_ input]
  {:pre-events [[::fetch-started]]
   :params {:request-method :get
            :url            (str "http://www.example.com/things/" (:id input))}
   :err-commands [[::toast!]]
   :ok-events ...})

;; define a request-fn
(defn my-request-fn [resource-type params]
  ;; returns a vector tuple or a core.async channel
  (async/go
    ;; does whatever, http prolly
    ...
    ;; succeeds with a vector tuple
    [:ok {:some :data}] ;; if it isn't `:ok`, it's an `:err`
    ;; or fails with a vector tuple
    [:err {:some :error}]))


;; resource key
(def resource-key [::fetch-thing ::numero-uno])

;; create your store your request handler
(def store (defacto/create (res/with-ctx {:some :ctx-map} my-request-fn) {}))
(def sub (defacto/subscribe store [::res/?:resource resource-key]))

;; submit the resource
(defacto/dispatch! store [::res/submit! resource-key {:id 123}])
@sub ;; => {:status :requesting ...}
... after the request finishes
@sub ;; => {:status :success :payload ...}
```

## What's a resource?

A `resource` is anything that can be interacted with via a simple asynchronous request/response model. Some example
use-cases might be an `HTTP` request, `database` call, or - in a browser - `localstorage`. A `resource`'s value
will have the following keys:

- `:state` - enum of `#{:init :requesting :success :error}`
  - `:init` - not yet requested (or subsequently destroyed)
  - `:requesting` - actively processing its request
  - `:success` - finished processing with a "success" status
  - `:error` - finished processing with an "error" status
- `:payload` (optional) - the data associated with the most recent request
- `:params` (optional) - the last params used to request the resource, if any

A `resource` is defined by extending [[defacto.resources.core/->resource-spec]] with your `resource-type` which
you can use to create and reference resources in the system.

```clojure
(defmethod defacto.resources.core/->request-spec ::my-resource-type
  [resource-key input]
  {:params {...}
   ...})
```

Your spec can return any of the following keys
- `:params` - a NON-`nil` argument to request the resource. If this key is `nil`, the resource will not be requested.
- `:pre-events`, `:pre-command` - optional sequences of events/commands to be emitted/dispatched before the request
  is submitted. These occur even if `:params` is `nil`
- `:ok-events`, `:ok-commands` - optional sequences of events/commands to emitted/dispatched after the request succeeds.
  These events/commands should be express "callback" style with the final argument to be the success result `conj`'ed on
  to the event/command vector.
- `:err-events`, `:err-commands` - optional sequences of events/commands to emitted/dispatched after the request fails.
  These events/commands should be express "callback" style with the final argument to be the error result `conj`'ed on
  to the event/command vector.

## Commands

This module exposes the following commands.

### [::res/submit! resource-key params]

This submits a resource with the provided params.

```clojure
(defacto/dispatch! store [::res/submit! ::resource-type {:a 1}])
```

### [::res/ensure! resource-key params]

This submits a resource if it is currently in the `:init` state.


```clojure
(defacto/dispatch! store [::res/ensure! ::resource-type {:a 1}])
```

### [::res/sync! resource-key params]

This submits a resource if it is in the `:init` state or the request `params` are different
from the previous submission's `params`.

```clojure
(defacto/dispatch! store [::res/sync! ::resource-type {:a 1}])
;; submits resource
(defacto/dispatch! store [::res/sync! ::resource-type {:a 1}])
;; does not submit resource
(defacto/dispatch! store [::res/sync! ::resource-type {:a 2}])
;; submits resource
```

### [::res/poll! milliseconds resource-key params]

Continuously submits a resource in intervals of `milliseconds`.

```clojure
;; sends a request now, and after every 2 seconds forever
(defacto/dispatch! store [::res/poll! 2000 ::resource-type {:a 1}])
;; destroy the resource to stop the polling
(defacto/emit! store [::res/destroyed ::resource-type])
```

### [::res/delay! milliseconds command]

Executes a command after `milliseconds` have expired.

```clojure
(defacto/dispatch! store [::res/delay! 123 [::any-command! {:a 1}]])
```

## Queries

### [::res/?:resource resource-key]

Retrieves the current state of a resource.

```clojure
@(defacto/subscribe store [::res/?:resource [::some-key 123]])
;; =? {:status :init}
;; returns `nil` for unknown resources
```

## Events

### [::submitted resource-key request-params]

Transitions the request from any state to `:requesting`. **Not intended to be used directly**

### [::succeeded resource-key data]

Transitions the request from a `:requesting` state to a `:success` state. **Not intended to be used directly**

### [::failed resource-key error]

Transitions the request from a `:requesting` state to an `:error` state. **Not intended to be used directly**

### [::destroyed resource-key]

Destroys a resource.

```clojure
(defacto/emit! store [::res/destroyed ::resource-type])
```
