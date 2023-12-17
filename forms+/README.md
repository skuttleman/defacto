# defacto-forms+

A module for combing the functionality of [defacto-forms](../forms/README.md) with [defacto-res](../res/README.md).

```clojure
;; deps.edn
{:deps {skuttleman/defacto-forms+ {:git/url   "https://github.com/skuttleman/defacto"
                                   :git/sha   "{SHA_OF_HEAD}"
                                   :deps/root "forms+"}}}
```

## An example using reagent

```clojure
(ns killer-app.core
  (:require
    [clojure.core.async :as async]
    [defacto.core :as defacto]
    [defacto.forms.core :as forms]
    [defacto.forms.plus :as forms+]
    [defacto.resources.core :as res]
    [reagent.core :as r]))

(defn request-fn [_resource-key params]
  (async/go
    ;; ... fulfills request
    [::res/ok {:some "result"}]))

(def store (defacto/create (res/with-ctx request-fn) {}))

(defn input [form+ path]
  [:input {:value     (get-in (forms/data form+) path)
           :on-change (fn [_]
                        (defacto/emit! store [::forms/changed [::forms+/std [::my-res-spec 123]]
                                              path
                                              (-> e .-target .-value)]))}])

(defn app []
  (r/with-let [sub (store/subscribe [::forms+/:form+ [::forms+/std [::my-res-spec 123]]])]
    (let [form+ @sub] ;; a `form+` is a `form` AND a `resource`
      (if (res/requesting? form+)
        [:div "loading..."]
        [:div
         [input form+ [:input-1]]
         [input form+ [:input :two]]
         [:button {:on-click (fn [_]
                               (defacto/dispatch! store [::forms+/submit! [::forms+/std [::my-res-spec 123]]
                                                         {:additional :input}]))}
          "Submit!"]]))
    (finally
      (defacto/emit! store [::forms+/destroyed]))))


;; a `form+` is wrapper around a normal defacto resource spec
(defmethod res/->request-spec ::my-res-spec
  [[_ id] {::forms/keys [data] :keys [additional] :as params}]
  ;; `id` is 123 (from the above usage)
  ;; ::forms/data is the current value of the form  (i.e. {:input-1 "foo" :input {:two "bar"}})
  ;; `params` contains other params passed to ::forms+/submit!
  {:params {:req :params} ;; this is what will be passed to `request-fn`
   :ok-events [...]})


;; when the resource "succeeds", the form+ is reset back it its initial state by default. Override that by extending forms+/re-init
(defmethod forms+/re-init ::my-res-spec
  [_resource-key form _res-result-data]
  (forms/data form))  ;; retains the form data that was submitted
```

## Form validation

The above example uses `::forms+/std`, but there is also `::forms+/valid` which will do local validation before submitting
the form. In order to use this form, you must extend `forms+/validate`.


```clojure
(defmethod forms+/validate ::my-res-spec
  [_resource-key form-data]
  ;; return `nil` when the form is VALID
  ;; any non-`nil` value is treated as INVALID
  {:key ["something is wrong"]})


;; now change the form-key in the above example to use `::forms+/valid` instead of `::forms+/std`
(defacto/subscribe [::forms+/:form+ [::forms+/valid [::my-res-spec 123]]])


;; if the form is invalid, the resource will not be submitted, and instead will fail with:
;; {::forms/errors return-val-from-validate} to distinguish
(res/payload @(defacto/subscribe [::forms+/:form+ [::forms+/valid [::my-res-spec 123]]]))
;; {::forms/errors {:key ["something is wrong"]}}
```

## Commands

This module exposes the following `commands`.

### [::forms+/submit! form-key ?params]

Submits the underlying resource with the current form data.

```clojure
(defacto/dispatch! store [::forms+/submit! [::forms+/valid [::my-res-spec 123]]])
```

## Queries

This module exposes the following `queries`.

### [::forms+/?:form+ form-key]

```clojure
@(defacto/subscribe store [::forms+/?form+ [::forms+/valid [::my-res-spec 123]]])
```

## Events

This module exposes the following `events`.

### [::forms+/recreated form-key res-result]

Re-initializes a `form+` after a successful submission. **Not intended to be used directly**

### [::forms+/destroyed form-key]

Destroys the `form+` (i.e. the underlying `form` and `resource`)

```clojure
(defacto/emit! store [::forms+/destroyed [::forms+/std [::my-res-spec 123]]])
```
