# defacto-forms

A simple module for isolating and maintaining arbitrary maps of user input.

```clojure
;; deps.edn
{:deps {skuttleman/defacto-forms  {:git/url   "https://github.com/skuttleman/defacto"
                                   :git/sha   "{SHA_OF_HEAD}"
                                   :deps/root "forms"}}}
```

```clojure
(ns killer-app.core
  (:require
    [defacto.core :as defacto]
    [defacto.forms.core :as forms]))


;; create a form of any map of data
(defacto/emit! store [::forms/created ::any-unique-id {:input "data"}])
(def sub (defacto/subscribe store [::forms/?:form ::any-unique-id]))
(defacto/emit! store [::forms/changed ::any-unique-id [:path :into :model] "value"])
(forms/data @sub)
;; => {:input "data" :path {:into {:model "value"}}}


;; paths with `integer`s expand into vectors. (do not use integer keys in maps)
(defacto/emit! store [::forms/changed ::any-unique-id [:other 1 :data] "thingy"])
(forms/data @sub)
;; => {:input "data" :path {:into {:model "value"}} :other [nil {:data "thingy"}]}


;; forms retain `nil` in leaf values be default, but that can be changed
(defacto/emit! store [::forms/created ::any-unique-id {:input "data"} {:remove-nil? true}])
(defacto/emit! store [::forms/changed ::any-unique-id [:path :into :model] "value"])
(forms/data @sub)
;; => {:input "data" :path {:into {:model "value"}}}
(defacto/emit! store [::forms/changed ::any-unique-id [:path :into :model] nil])
(forms/data @sub)
;; => {:input "data"}
```

See [forms+](../forms+/README.md) for more possibilities.

## Commands

This module exposes the following `commands`.

### [::forms/ensure! form-id data ?opts]

Creates a form if there isn't already one with the supplied `form-id`.

```clojure
(defacto/dispatch! store [::forms/ensure! ::unique-form-id {:some "data"}])
```

## Queries

This module exposes the following `queries`.

### [::forms/?:form form-id]

Queries the current form.

```clojure
@(defacto/subscribe store [::forms/?:form ::unique-form-id])
```

## Events

This module exposes the following `events`.

### [::forms/created form-id data >opts]

Creates a new form, clobbering an existing form with the same id if it exists.

```clojure
(defacto/emit! store [::forms/created ::unique-form-id {:some "data"}])
```

### [::forms/changed form-id path value]

Changes the value at a path into your data model.

### [::forms/modified form-id path f arg1 arg2 ...]

Modifies the value at a path in your data model by applying a function and additional args.

```clojure
(defacto/emit! store [::forms/changed ::unique-form-id [:some :path] "value"])
```

### [::forms/destroyed form-id]

Removes a form from the db.

```clojure
(defacto/emit! store [::forms/destroyed ::unique-form-id])
```
