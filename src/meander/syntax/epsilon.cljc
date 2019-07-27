(ns meander.syntax.epsilon
  #?(:clj
     (:require [clojure.core.specs.alpha :as core.specs]
               [clojure.set :as set]
               [clojure.spec.alpha :as s]
               [clojure.spec.gen.alpha :as s.gen]
               [clojure.string :as string]
               [cljs.tagged-literals]
               [meander.util.epsilon :as r.util])
     :cljs
     (:require [cljs.core.specs.alpha :as core.specs]
               [cljs.spec.alpha :as s :include-macros true]
               [cljs.spec.gen.alpha :as s.gen :include-macros true]
               [clojure.set :as set]
               [clojure.string :as string]
               [meander.util.epsilon :as r.util]))
  #?(:cljs
     (:require-macros [meander.syntax.epsilon]))
  #?(:clj
     (:import (cljs.tagged_literals JSValue))))

#?(:clj (set! *warn-on-reflection* true))


;; ---------------------------------------------------------------------
;; 

(def ^{:dynamic true}
  *form* nil)

(def ^{:dynamic true}
  *env* {})

(defonce global-expander-registry
  (atom {}))

(defn register-expander
  [symbol f]
  {:pre [(symbol? symbol)
         (or (fn? f)
             (and (var? f) (fn? (deref f))))]}
  (swap! global-expander-registry assoc symbol f)
  nil)

(defonce global-parser-registry
  (atom {}))

(defn register-parser
  [symbol f]
  {:pre [(symbol? symbol)
         (or (fn? f)
             (and (var? f) (fn? (deref f))))]}
  (swap! global-parser-registry assoc symbol f)
  nil)

;; ---------------------------------------------------------------------
;; AST specs and predicates

;; Every AST node has, at least, the key `:tag`, the value of which is
;; a `keyword?`.

(s/def :meander.syntax.epsilon.node/tag
  keyword?)

;; Some nodes may have an `::original-form` key, the value of which is
;; `any?`. This key is populated by forms which have been expanded
;; during `parse` and then subsequently parsed into an AST node.

(s/def :meander.syntax.epsilon.node/original-form
  any?)

(s/def :meander.syntax.epsilon/node
  (s/keys :req-un [:meander.syntax.epsilon.node/tag]
          :opt [:meander.syntax.epsilon.node/original-form]))

;; Any patterns
;; ------------
;;
;; Any patterns are `simple-symbol?`s which start with an `_`. They
;; match anything.

(defn any-form?
  "true if x is a symbol beginning with _."
  [x]
  (and (simple-symbol? x)
       (r.util/re-matches? #"_.*" (name x))))

(s/def :meander.syntax.epsilon/any-form
  (s/with-gen
    (s/conformer
     (fn [x]
       (if (any-form? x)
         x
         ::s/invalid))
     identity)
    (fn []
      (s.gen/fmap
       (fn [sym]
         (symbol (str "_" (name sym))))
       (s.gen/symbol)))))

(s/def :meander.syntax.epsilon.node.any/tag
  #{:any})

(s/def :meander.syntax.epsilon.node.any/symbol
  :meander.syntax.epsilon/any-form)

(s/def :meander.syntax.epsilon.node/any
  (s/keys :req-un [:meander.syntax.epsilon.node.any/tag
                   :meander.syntax.epsilon.node.any/symbol]))

(defn any-node?
  "true if x is an :any node, false otherwise."
  [x]
  (s/valid? :meander.syntax.epsilon.node/any x))

(defn logic-variable-form?
  "true if x is in the form of a logic variable i.e. a simple symbol
  with a name beginning with \\?."
  [x]
  (and (simple-symbol? x)
       (r.util/re-matches? #"\?.+" (name x))))

(s/fdef logic-variable-form?
  :args (s/cat :x any?)
  :ret boolean?)

(s/def :meander.syntax.epsilon/logic-variable
  (s/with-gen
    (s/conformer
     (fn [x]
       (if (logic-variable-form? x)
         x
         ::s/invalid))
     identity)
    (fn []
      (s.gen/fmap
       (fn [x]
         (symbol (str \? (name x))))
       (s/gen simple-symbol?)))))

(s/def :meander.syntax.epsilon.node.lvr/tag
  #{:lvr})

(s/def :meander.syntax.epsilon.node.lvr/symbol
  :meander.syntax.epsilon/logic-variable)

(s/def :meander.syntax.epsilon.node/lvr
  (s/keys :req-un [:meander.syntax.epsilon.node.lvr/tag
                   :meander.syntax.epsilon.node.lvr/symbol]))

(defn lvr-node?
  [x]
  (s/valid? :meander.syntax.epsilon.node/lvr x))

(defn memory-variable-form?
  "true if x is in the form of a memory variable i.e. a simple symbol
  with a name beginning with \\!."
  [x]
  (and (simple-symbol? x)
       (r.util/re-matches? #"!.+" (name x))))

(s/def :meander.syntax.epsilon/memory-variable
  (s/with-gen
    (s/conformer
     (fn [x]
       (if (memory-variable-form? x)
         x
         ::s/invalid))
     identity)
    (fn []
      (s.gen/fmap
       (fn [x]
         (symbol (str \! (name x))))
       (s/gen simple-symbol?)))))

(s/def :meander.syntax.epsilon.node.mvr/tag
  #{:mvr})

(s/def :meander.syntax.epsilon.node.mvr/symbol
  :meander.syntax.epsilon/memory-variable)

(s/def :meander.syntax.epsilon.node/mvr
  (s/keys :req-un [:meander.syntax.epsilon.node.mvr/tag
                   :meander.syntax.epsilon.node.mvr/symbol]))

(defn mvr-node?
  [x]
  (s/valid? :meander.syntax.epsilon.node/mvr x))

(defn variable-node?
  [x]
  (or (mvr-node? x)
      (lvr-node? x)))

(defn ref-sym?
  [x]
  (and (simple-symbol? x)
       (boolean (re-matches #"%.+" (name x)))))

(s/def :meander.syntax.epsilon/reference
  (s/with-gen
    (s/conformer
     (fn [x]
       (if (ref-sym? x)
         x
         ::s/invalid))
     identity)
    (fn []
      (s.gen/fmap
       (fn [x]
         (symbol (str \% (name x))))
       (s/gen simple-symbol?)))))

(s/def :meander.syntax.epsilon.node.ref/tag
  #{:ref})

(s/def :meander.syntax.epsilon.node.ref/symbol
  :meander.syntax.epsilon/reference)

(s/def :meander.syntax.epsilon.node/ref
  (s/keys :req-un [:meander.syntax.epsilon.node.ref/symbol
                   :meander.syntax.epsilon.node.ref/tag]))

(defn ref-node?
  "true if x is a :ref node, false otherwise."
  [x]
  (s/valid? :meander.syntax.epsilon.node/ref x))

(s/def :meander.syntax.epsilon.node.with/tag
  #{:wth})

(s/def :meander.syntax.epsilon.node.with.binding/ref
  :meander.syntax.epsilon.node/ref)

(s/def :meander.syntax.epsilon.node.with.binding/pattern
  :meander.syntax.epsilon/node)

(s/def :meander.syntax.epsilon.node.with/binding
  (s/keys :req-un [:meander.syntax.epsilon.node.with.binding/pattern
                   :meander.syntax.epsilon.node.with.binding/ref]))

(s/def :meander.syntax.epsilon.node.with/bindings
  (s/coll-of :meander.syntax.epsilon.node.with/binding
             :kind sequential?))

(s/def :meander.syntax.epsilon.node.with/body
  (s/nilable :meander.syntax.epsilon/node))

(s/def :meander.syntax.epsilon.node/with
  (s/keys :req-un [:meander.syntax.epsilon.node.with/tag
                   :meander.syntax.epsilon.node.with/bindings]
          :opt-un [:meander.syntax.epsilon.node.with/body]))

(defn with-node? [x]
  (s/valid? :meander.syntax.epsilon.node/with x))


(s/def :meander.syntax.epsilon.node.partition/left
  :meander.syntax.epsilon/node)

(s/def :meander.syntax.epsilon.node.partition/right
  :meander.syntax.epsilon/node)

(s/def :meander.syntax.epsilon.node.partition/right
  :meander.syntax.epsilon/node)

(s/def :meander.syntax.epsilon.node/partition
  (s/keys :req-un [:meander.syntax.epsilon.node.partition/left
                   :meander.syntax.epsilon.node.partition/right]
          :opt-un [:meander.syntax.epsilon.node.partition/as]))

(defn partition-node? [x]
  (s/valid? :meander.syntax.epsilon.node/partition x))

(defn empty-cat-node? [x]
  (and (= :cat (:tag x))
       (not (seq (:elements x)))))

(defn tail-node? [x]
  (and (= :tail (:tag x))
       (some? (:pattern x))))

(defn node?
  "true if x is an AST node."
  [x]
  (s/valid? :meander.syntax.epsilon/node x))

;; ---------------------------------------------------------------------
;; AST API

(defn tag
  "Return the tag of node."
  [node]
  (s/assert :meander.syntax.epsilon/node node)
  (:tag node))

;; children
;; --------

(s/fdef children
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret (s/coll-of :meander.syntax.epsilon/node
                  :kind sequential?))

(defmulti children
  "Return a sequential? of all children of node."
  {:arglists '([node])}
  #'tag)

(defmethod children :default
  [node]
  [])

(defn subnodes
  "Return a sequence of all subnodes of node."
  [node]
  (cons node (mapcat subnodes (children node))))

(defn proper-subnodes
  "Return the all subnodes in node excluding node."
  [node]
  (rest (subnodes node)))

(defmulti min-length
  "The minimum possible length the pattern described by `node` can be.
  Note, this multimethod will throw an error whenever `node` does not
  have a method to handle it. This behavior is intentional as the
  implementations should only exist for things which have can have
  length. The `min-length?` predicate can be used to detect if `node`
  implements `min-length`."
  {:arglists '([node])}
  #'tag)

(s/fdef min-length
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret (s/or :nat nat-int?
             :inf #{##Inf}))

(defn min-length?
  "true if `x` implements `min-length`, false otherwise."
  [x]
  (and (node? x)
       (contains? (methods min-length) (tag x))))

(s/fdef min-length?
  :args (s/cat :x any?)
  :ret boolean?)

(defmulti max-length
  "The maximum possible length the pattern described by `node` can
  be. Note, this multimethod will throw an error whenever `node` does
  not have a method to handle it. This behavior is intentional as the
  implementations should only exist for things which have can have
  length. The `max-length?` predicate can be used to detect if `node`
  implements `max-length`."
  {:arglists '([node])}
  #'tag)

(s/fdef max-length
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret (s/or :nat nat-int?
             :inf #{##Inf}))

(defn max-length?
  "true if `x` implements `max-length`, false otherwise."
  [x]
  (and (node? x)
       (contains? (methods max-length) (tag x))))

(s/fdef max-length?
  :args (s/cat :x any?)
  :ret boolean?)

(s/fdef variable-length?
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret boolean?)

(defn variable-length?
  "true if node may have a variable length, false otherwise. Note this
  function will throw an error if node does not implement methods for
  both min-length and max-length."
  [node]
  (not (= (min-length node) (max-length node))))

(defmulti ground?
  "true if node is ground i.e. it contains no variables or is not a
  match operator."
  {:arglists '([node])}
  #'tag)

(s/fdef search?
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret boolean?)

(defmulti search?
  "true if node represents a search, false otherwise."
  {:arglists '([node])}
  #'tag)

(defn unparse-dispatch
  {:private true}
  [node]
  (if (contains? node ::original-form)
    ::original-form
    (tag node)))

(defmulti unparse
  "In pre-order fashion rewrite a node into a Clojure form."
  {:arglists '([node])}
  #'unparse-dispatch)

(defmethod unparse ::original-form
  [node]
  (::original-form node))

(defn fold
  "Same as clojure.core/reduce but specifically for ASTs. f must be a
  binary (arity 2) function and will receive as arguments the current
  accumulated value and an AST node. fold is eager and will visit each
  subnode in node."
  [f value node]
  (reduce f value (subnodes node)))

(defn variables*
  {:private true}
  [node]
  (fold
   (fn [vars node]
     (let [tag (tag node)]
       (case tag
         (:lvr :mut :mvr :ref)
         (update vars tag conj node)

         :wth
         (transduce (comp (map :pattern)
                          (map variables*))
                    (fn
                      ([a] a)
                      ([a b]
                       (merge-with set/union a b)))
                    vars
                    (:bindings node))

         ;; else
         vars)))
   {:lvr #{}
    :mut #{}
    :mvr #{}
    :ref #{}}
   node))

(defn variables
  "Return all :lvr and :mvr nodes in node."
  [node]
  (s/assert :meander.syntax.epsilon/node node)
  (let [vars (variables* node)]
    (set/union (:lvr vars) (:mvr vars))))

(defn memory-variables
  "Return all :mvr nodes in node."
  [node]
  (s/assert :meander.syntax.epsilon/node node)
  (:mvr (variables* node)))

(defn logic-variables
  "Return all :lvr nodes in node."
  [node]
  (s/assert :meander.syntax.epsilon/node node)
  (:lvr (variables* node)))


(s/fdef references
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret (s/coll-of :meander.syntax.epsilon.node/ref
                  :kind set?
                  :into #{}))

(defn references
  "Return all :ref nodes in node."
  [node]
  (:ref (variables* node)))

(defn top-level
  [node]
  (case (tag node)
    (:cnj :dsj :wth)
    (mapcat top-level (children node))
    ;; else
    [node]))

;; ---------------------------------------------------------------------
;; Parse implementation

(declare parse)

(def default-env
  {::expander-registry {}
   ::parser-registry {}})

(defn expand-symbol
  {:private true}
  [sym env]
  #?(:clj (if-some [cljs-ns (:ns env)]
            ;; ClojureScript compile-time
            (if (qualified-symbol? sym)
              (let [ns-sym (symbol (namespace sym))]
                (if-some [ns (get (:requires cljs-ns) ns-sym)]
                  (symbol (name ns) (name sym))
                  sym))
              (if (contains? (:defs cljs-ns) sym)
                (symbol (name (:name cljs-ns)) (name sym))
                sym))
            ;; Clojure
            (if (qualified-symbol? sym)
              (let [ns-sym (symbol (namespace sym))]
                (if-some [ns (get (ns-aliases *ns*) ns-sym)]
                  (symbol (name (ns-name ns)) (name sym))
                  sym))
              (symbol (name (ns-name *ns*)) (name sym))))
     :cljs (if-some [cljs-ns (:ns env)]
             (if (qualified-symbol? sym)
               (let [ns-sym (symbol (namespace sym))]
                 (if-some [ns (get (:requires cljs-ns) ns-sym)]
                   (symbol (name ns) (name sym))
                   sym))
               (if (contains? (:defs cljs-ns) sym)
                 (symbol (name (:name cljs-ns)) (name sym))
                 sym))
             sym)))

(s/fdef expand-symbol
  :args (s/cat :sym symbol?
               :env :meander.syntax.epsilon/env)
  :ret symbol?)

;;; Syntax expansion

(s/def :meander.syntax.epsilon/expander-registry
  (s/map-of symbol? fn?))

(defn expander-registry
  "Return the `::expander-registry` of the environment `env` or `nil`
  if it cannot be found."
  [env]
  (let [x (get env ::expander-registry)]
    (if (map? x)
      x)))

(defn resolve-expander
  "Return the `::expander` associated with `sym` with respect to the
  environment `env`."
  [sym env]
  (let [x (get (expander-registry env) (expand-symbol sym env))]
    (if (fn? x)
      x)))

(s/fdef resolve-expander
  :args (s/cat :sym symbol?
               :env :meander.syntax.epsilon/env)
  :ret (s/alt :fn fn?
              :nil nil?))

(defn expand-form
  "Expand `form` with respect to `env` if possible. Returns the result
  of expanding `form` or `form` unchanged."
  [form env]
  (if (seq? form)
    (let [head (first form)]
      (if (symbol? head)
        (let [expander (resolve-expander head env)]
          (if (fn? expander)
            (expander form env)
            form))
        form))
    form))

(s/fdef expand-form
  :args (s/cat :form any?
               :env :meander.syntax.epsilon/env)
  :ret any?)

;;; Syntax parsing

(s/def :meander.syntax.epsilon/parser-registry
  (s/map-of symbol? fn?))

(defn parser-registry
  "Return the `::parser-registry` of the environment `env` or `nil` if
  it cannot be found."
  [env]
  (let [x (get env ::parser-registry)]
    (if (map? x)
      x)))

(defn resolve-parser
  "Return the `::parser` associated with `sym` with respect to the
  environment `env`."
  [sym env]
  (let [x (get (parser-registry env) (expand-symbol sym env))
        x (if (var? x)
            (deref x)
            x)]
    (if (fn? x)
      x)))

(s/fdef resolver-parser
  :args (s/cat :sym symbol?
               :env :meander.syntax.epsilon/env)
  :ret (s/alt :fn fn?
              :nil nil?))

(s/def :meander.syntax.epsilon/env
  (s/keys :req-un [:meander.syntax.epsilon/expander-registry
                   :meander.syntax.epsilon/parser-registry]
          :opt-un [:meander.syntax.epsilon/phase]))

(defn parse-all
  "Apply `parse` to all forms in the sequence `forms`."
  [forms env]
  (map (fn [form] (parse form env)) forms))

(s/fdef parse-all
  :args (s/cat :forms (s/coll-of any?
                                 :kind sequential?)
               :env :meander.syntax.epsilon/parse-env)
  :ret (s/coll-of :meander.syntax.epsilon/node
                  :kind sequential?))

(defn expand-prt
  {:private true}
  [xs]
  (let [[l r] (split-with
               (fn [{tag :tag}]
                 (case tag
                   (:dot :dt+ :dt* :dtl :dtm)
                   false
                   ;; else
                   true))
               xs)]
    (if (seq l)
      (let [node (first r)]
        {:tag :prt
         :left (case (:tag node)
                 :dt*
                 (let [c (bounded-count 2 l)]
                   (cond
                     ;; _ ...
                     (and (= c 1)
                          (= :any (:tag (first l))))
                     {:tag :drp
                      :symbol (:symbol (first l))}

                     ;; !xs ...
                     (and (= c 1)
                          (= :mvr (:tag (first l))))
                     {:tag :rst
                      :mvr (first l)}

                     ;; a b ...
                     :else
                     {:tag :rp*
                      :cat {:tag :cat
                            :elements l}}))

                 ;; a b ..<nat-int>
                 :dt+
                 {:tag :rp+
                  :cat {:tag :cat
                        :elements l}
                  :n (:n node)}

                 ;; ab ..?<name>
                 :dtl
                 {:tag :rpl
                  :cat {:tag :cat
                        :elements l}
                  :lvr (:lvr node)}

                 ;; a b ..!<name>
                 :dtm
                 {:tag :rpm
                  :cat {:tag :cat
                        :elements l}
                  :mvr (:mvr node)}

                 (nil :dot)
                 {:tag :cat
                  :elements l})
         :right (expand-prt (next r))})
      (if (seq r)
        (let [node (first r)]
          {:tag :prt
           :left (case (:tag node)
                   :dt*
                   {:tag :rp*
                    :cat {:tag :cat
                          :elements l}}

                   :dt+
                   {:tag :rp+
                    :cat {:tag :cat
                          :elements l}
                    :n (:n node)}

                   (nil :dot)
                   {:tag :cat
                    :elements l})
           :right (expand-prt (next r))})
        {:tag :cat
         :elements []}))))

(defn prt-append
  {:private true}
  [prt node]
  (cond
    (partition-node? prt)
    (if (partition-node? (:right prt))
      (update prt :right prt-append node)
      (if (empty-cat-node? (:right prt))
        (assoc prt :right node)
        (assoc prt :right
               {:tag :prt
                :left (:right prt)
                :right node})))

    (empty-cat-node? prt)
    {:tag :prt
     :left node
     :right {:tag :cat
             :elements []}}))

(defmulti expand-seq
  {:arglists '([seq env])}
  (fn [xs env]
    (if (seq? xs)
      (let [x (first xs)]
        (if (symbol? x)
          (expand-symbol x env)
          ::default))
      ::default)))

(defmethod expand-seq :default
  [xs env]
  xs)

(defn parse-contain
  {:private true}
  [xs env]
  (if (and (seq? xs) (= (first xs) '$))
    (case (long (bounded-count 2 (rest xs)))
      1
      {:tag :ctn
       :pattern (parse (nth xs 1) env)}

      2
      {:tag :ctn
       ;; Should be an :lvr or :mvr.
       :context (parse (nth xs 1) env)
       :pattern (parse (nth xs 2) env)}
      ;; else
      (throw (ex-info "$ expects one or two arguments"
                      {:form xs
                       :meta (meta xs)})))
    (parse xs env)))

(defn parse-with
  {:private true}
  [xs env]
  (if (and (seq? xs) (= 'with (first xs)))
    (let [bindings (nth xs 1)]
      (if (and (vector? bindings)
               (even? (count bindings))
               (every? ref-sym? (take-nth 2 bindings)))
        {:tag :wth
         :bindings (map
                    (fn [[ref-sym x]]
                      {:ref {:tag :ref
                             :symbol ref-sym}
                       :pattern (parse x env)})
                    (partition 2 bindings))
         :body (let [nothing (gensym)
                     x (nth xs 2 nothing)]
                 (if (identical? x nothing)
                   nil
                   (parse x env)))}
        (throw
         (ex-info "second argument to with must be vector of the form [%ref-name pattern ...]"
                  {:form xs
                   :meta (meta xs)}))))
    (parse xs env)))

(defn parse-as
  {:private true}
  [xs env]
  (let [c (count xs)
        as-index (- c 2)]
    (if (and (<= 2 c)
             (= (nth xs as-index) :as))
      (let [xs* (take as-index xs)
            as-pattern (last xs)
            as-node (parse as-pattern env)]
        (case (:tag as-node)
          (:lvr :mvr)
          (let [;; Check for illegal :as pattern.
                as-result (parse-as xs* env)]
            (case (nth as-result 0)
              (:failure :success)
              [:failure ":as pattern may only occur once"]
              ;; else
              [:success xs* as-node]))
          ;; else
          [:failure ":as pattern must be a logic variable or memory variable"]))
      [:nothing xs nil])))

(defn parse-&
  {:private true}
  [xs env]
  (let [c (count xs)
        &-index (- c 2)]
    (if (and (<= 2 c)
             (= (nth xs &-index) '&))
      (let [xs* (take &-index xs)
            &-pattern (last xs)
            &-node (parse &-pattern env)]
        (let [;; Check for illegal :as pattern.
              as-result (parse-as xs* env)]
          (case (nth as-result 0)
            (:failure :success)
            [:failure "& pattern must appear be before :as pattern"]

            ;; else
            (let [;; Check for illegal & pattern.
                  &-result (parse-& xs* env)]
              (case (nth &-result 0)
                (:failure :sucess)
                [:failure "& pattern may only occur once"]

                ;; else
                [:success xs* &-node])))))
      [:success xs nil])))

(defn parse-sequential
  "Used by `parse-seq-no-head` and `parse-vector` to parse their
  `:prt` and `:as` nodes."
  {:private true}
  [xs env]
  ;; Check for :as ?x or :as !xs
  (let [as-result (parse-as xs env)]
    (case (nth as-result 0)
      :failure
      (throw (ex-info (nth as-result 1)
                      {:form xs
                       :meta (meta xs)}))

      (:success :nothing)
      (let [[_ xs* as-node] as-result
            ;; Check for & ?x or & !xs
            &-result (parse-& xs* env)]
        (case (nth &-result 0)
          :failure
          &-result

          (:success :nothing)
          (let [[_ xs** rest-node] &-result
                prt (expand-prt (parse-all xs** env))
                prt (if rest-node
                      (prt-append prt {:tag :tail
                                       :pattern rest-node})
                      prt)]
            [:success prt as-node]))))))

(defn parse-seq-no-head
  {:private true}
  [xs env]
  (let [result (parse-sequential xs env)]
    (case (nth result 0)
      :failure
      (let [[_ error-message] result]
        (throw (ex-info error-message
                        {:form xs
                         :meta (meta xs)})))

      :success
      (let [[_ prt as-node] result]
        {:tag :seq
         :prt prt
         :as as-node}))))

(defn parse-seq
  "Parses a seq? into a :meander.syntax.epsilon/node.

  seqs? of the following form are handled specially, all other seqs
  are parsed as :seq nodes.

    ($ <pattern>)
    ($ ?<context-name> <pattern>)
    (quote <form>)
    (with [%<simple-symbol> <pattern> ...] <pattern>)
    (clojure.core/unquote <form>)
    (clojure.core/unquote-splicig <form>)
    (<symbol*> <form_0> ... <form_n>)

  where symbol* is a fully qualified symbol with respect to the
  current namespace."
  [xs env]
  (let [x (first xs)]
    (if (symbol? x)
      (case x
        $
        (parse-contain xs env)

        quote
        {:tag :quo
         :form (second xs)}

        with
        (parse-with xs env)

        clojure.core/unquote
        {:tag :unq
         :expr (second xs)}

        clojure.core/unquote-splicing
        {:tag :uns
         :expr (second xs)}

        ;; else
        (let [xs* (expand-form xs env)]
          (if (= xs* xs)
            ;; Syntax expansion failed, try to parse special form.
            (let [head (first xs)
                  parser (if (symbol? head)
                           (resolve-parser head env))]
              (if (fn? parser)
                (let [node (parser xs env)]
                  (if (node? node)
                    ;; Special form, return the node.
                    (assoc node ::original-form xs)
                    (throw (ex-info ":meander.syntax.epsilon/parse-syntax function must return a :meander.syntax.epsilon/node"
                                    {:form xs
                                     :parse-env env}))))
                ;; Not a special form, parse as ordinary seq pattern.
                (parse-seq-no-head xs env)))
            ;; Syntax expansion successful, recursively parse the
            ;; result.
            (assoc (parse xs* env) ::original-form xs))))
      (parse-seq-no-head xs env))))

(defn parse-symbol
  {:private true}
  [sym]
  (if (namespace sym)
    {:tag :lit
     :value sym}
    (let [s (name sym)
          [$0 $N $L $M] (re-matches #"\.(?:\.(?:\.|(\d+)|(\?.+)|(!.+))?)?" s)]
      (cond
        ;; `..<nat-int>`
        (some? $N)
        (if (= $N "0")
          ;; `..0` is the same as `...`.
          {:tag :dt*}
          ;; Inteneral tag for postfix n or more operator.
          {:tag :dt+
           :n (r.util/parse-int $N)})

        ;; `..?<name>`
        (some? $L)
        ;; Internal tag for postfix ?n or more operator.
        {:tag :dtl
         :lvr {:tag :lvr
               :symbol (symbol $L)}}

        (some? $M)
        ;; Internal tag for postfix !n or more operator.
        {:tag :dtm
         :mvr {:tag :mvr
               :symbol (symbol $M)}}

        :else
        (case $0
          ;; Internal tag for postfix partition.
          "."
          {:tag :dot}

          ;; Internal tag for postfix n or more operator.
          ".."
          {:tag :dt+
           :n $N}

          ;; Internal tag for postfix 0 or more operator.
          "..."
          {:tag :dt*}

          nil
          (cond
            (r.util/re-matches? #"^_.*" s)
            {:tag :any
             :symbol sym}

            (r.util/re-matches? #"^\?.+" s)
            {:tag :lvr
             :symbol sym}

            (r.util/re-matches? #"^!.+" s)
            {:tag :mvr
             :symbol sym}

            (r.util/re-matches? #"^%.+" s)
            {:tag :ref
             :symbol sym}

            (r.util/re-matches? #"^\*.+" s)
            {:tag :mut
             :symbol sym}

            :else
            {:tag :lit
             :value sym}))))))

(defn parse-js-value
  {:private true}
  [^JSValue js-value env]
  (let [x (.val js-value)]
    (cond
      (vector? x)
      {:tag :jsa
       :prt (expand-prt (parse-all x env))}

      (map? x)
      {:tag :jso
       :object (into {}
                     (map
                      (fn [[k v]]
                        (let [k* (if (keyword? k)
                                   (subs (str k) 1)
                                   k)]
                          [(parse k* env) (parse v env)])))
                     x)})))

(defn parse-vector
  {:private true}
  [v env]
  (let [result (parse-sequential v env)]
    (case (nth result 0)
      :failure
      (let [[_ error-message] result]
        (throw (ex-info error-message
                        {:form v
                         :meta (meta v)})))

      :success
      (let [[_ prt as-node] result]
        {:tag :vec
         :prt prt
         :as as-node}))))

(defn parse-map
  {:private true}
  [m env]
  (if (and (map? m)
           (not (record? m)))
    (let [as (if-some [[_ y] (find m :as)]
               (if (or (logic-variable-form? y)
                       (memory-variable-form? y))
                 (parse y env)))
          m (if (some? as)
              (dissoc m :as)
              m)
          rest-map (if-some [[_ y] (find m '&)]
                     (parse y env))
          m (if (some? rest-map)
              (dissoc m '&)
              m)]
      {:tag :map
       :as as
       :rest-map rest-map
       :map (into {}
                  (map
                   (fn [[k v]]
                     [(parse k env) (parse v env)]))
                  m)})
    (parse m env)))

(defn parse-set [s env]
  (if (set? s)
    (let [as-form (some
                   (fn [x]
                     (when (= (:as (meta x)) true)
                       x))
                   s)
          s (if (some? as-form)
              (disj s as-form)
              s)
          rest-form (some
                     (fn [x]
                       (when (= (:tag (meta x)) '&)
                         x))
                     s)
          s (if (some? rest-form)
              (disj s rest-form)
              s)]
      {:tag :set
       :as (if (some? as-form)
             (parse as-form env))
       :rest (if (some? rest-form)
               (parse rest-form env))
       :elements (parse-all s env)})
    (parse s env)))

(defn parse
  "Parse `form` into an abstract syntax tree (AST) optionally with
  respect to the environment `env`.

  (parse '(?x1 ?x2 :as ?xs))
  ;; =>
  {:tag :seq
   :as {:tag :lvr
        :symbol ?xs}
   :prt {:tag :prt
         :left {:tag :cat
                :elements ({:tag :lvr :symbol ?x1}
                           {:tag :lvr :symbol ?x2})}
         :right {:tag :cat
                 :elements []}}}"
  ([form]
   (parse form {}))
  ([form env]
   (let [node (cond
                (seq? form)
                (parse-seq form env)

                (vector? form)
                (parse-vector form env)

                (and (map? form)
                     (not (record? form)))
                (parse-map form env)

                (set? form)
                (parse-set form env)

                (symbol? form)
                (parse-symbol form)

                #?@(:clj [(instance? JSValue form)
                          (parse-js-value form env)])

                :else
                {:tag :lit
                 :value form})]
     (if-some [meta (meta form)]
       (with-meta node meta)
       node))))

(s/fdef parse
  :args (s/alt :a1 (s/cat :form any?)
               :a2 (s/cat :form any?
                          :env :meander.syntax.epsilon/parse-env))
  :ret :meander.syntax.epsilon/node)


;; ---------------------------------------------------------------------
;; AST method implementations

;; :any

(defmethod ground? :any [_]
  false)

(defmethod unparse :any [_]
  '_)

(defmethod search? :any [_]
  false)

;; :cat

(defmethod ground? :cat [node]
  (every? ground? (:elements node)))

(defmethod children :cat [node]
  (:elements node))

(defmethod min-length :cat [node]
  (count (:elements node)))

(defmethod max-length :cat [node]
  (count (:elements node)))

(defmethod unparse :cat [node]
  (apply list (map unparse (:elements node))))

(defmethod search? :cat [node]
  (boolean (some search? (:elements node))))

;; :ctn

(defmethod children :ctn [node]
  (if-some [[_ pattern] (find node :pattern)]
    [pattern]
    []))

(defmethod ground? :ctn [_]
  false)

(defmethod unparse :ctn [node]
  `(~'$
    ~@(if-some [[_ context] (find node :context)]
        [(unparse context)])
    ~@(if-some [[_ pattern] (find node :pattern)]
        [(unparse pattern)])))

(defmethod search? :ctn [_]
  true)

;; :drp

(defmethod ground? :drp [_]
  false)

(defmethod min-length :drp [_]
  0)

(defmethod max-length :drp [_]
  ##Inf)

(defmethod unparse :drp [node]
  (list (:symbol node) '...))

(defmethod search? :drp [_]
  false)

;; :jsa

(defmethod children :jsa [node]
  [(:prt node)])

(defmethod ground? :jsa [node]
  (ground? (:prt node)))

(defmethod min-length :jsa [node]
  (min-length (:prt node)))

(defmethod max-length :jsa [node]
  (max-length (:prt node)))

(defmethod unparse :jsa [node]
  #?(:clj
     (JSValue. (vec (unparse (:prt node))))
     :cljs
     (into-array (unparse (:prt node)))))

(defmethod search? :jsa [node]
  (search? (:prt node)))

;; :jso

(defmethod children :jso [node]
  (mapcat identity (:object node)))

(defmethod ground? :jso [node]
  (every?
   (fn [[k v]]
     (and (ground? k)
          (ground? v)))
   (:object node)))

(defmethod unparse :jso [node]
  #?(:clj
     (JSValue. (reduce-kv
                (fn [m k v]
                  (assoc m (unparse k) (unparse v)))
                {}
                (:object node)))
     :cljs
     (reduce-kv
      (fn [obj [k v]]
        (doto obj
          (goog.object/set (unparse k) (unparse v))))
      #js {}
      (:object node))))

(defmethod search? :jso [node]
  (boolean
   (some
    (fn [[k v]]
      (or (not (ground? k))
          (search? k)
          (search? v)))
    (:object node))))


;; :lit

(defmethod ground? :lit [_]
  true)

(defn unparse-lit
  {:private true}
  [x]
  (cond
    (symbol? x)
    `(quote ~x)

    (seq? x)
    (if (= (first x) 'quote)
      x
      (if (= (first x) `list)
        (cons (first x) (map unparse-lit (rest x)))
        (if (seq x)
          (cons `list (map unparse-lit x))
          ())))

    (map? x)
    (into {}
          (map
           (fn [[k v]]
             [(unparse-lit k) (unparse-lit v)]))
          x)

    (coll? x)
    (into (empty x) (map unparse-lit) x)

    :else
    x))

(defmethod unparse :lit [node]
  (unparse-lit (:value node)))

(defmethod search? :lit [_]
  false)

;; :lvr

(defmethod ground? :lvr [_]
  false)

(defmethod unparse :lvr [node]
  (:symbol node))

(defmethod search? :lvr [_]
  false)

;; :map

(defmethod children :map [node]
  (concat (mapcat identity (:map node))
          (if-some [rest-map (:rest-map node)]
            (let [xs (children rest-map)]
              (if (seq xs)
                xs
                [rest-map])))
          (if-some [as (:as node)]
            (let [xs (children as)]
              (if (seq xs)
                xs
                [as])))))

(defmethod ground? :map [node]
  (every?
   (fn [[k v]]
     (and (ground? k)
          (ground? v)))
   (:map node)))

(defmethod unparse :map [node]
  (cond-> (reduce-kv
           (fn [m k v]
             (assoc m (unparse k) (unparse v)))
           {}
           (:map node))
    (some? (:as node))
    (assoc :as (:as node))

    (some? (get node '&))
    (assoc '& (unparse (get node '&)))))

(defmethod search? :map [node]
  (boolean
   (some
    (fn [[k v]]
      (or (not (ground? k))
          (search? k)
          (search? v)))
    (:map node))))

;; :mut

(defmethod ground? :mut [_]
  false)

(defmethod unparse :mut [node]
  (:symbol node))

(defmethod search? :mut [_]
  false)

;; :mvr

(defmethod ground? :mvr [_]
  false)

(defmethod unparse :mvr [node]
  (:symbol node))

(defmethod search? :mvr [_]
  false)

;; :prt

(defmethod children :prt [node]
  [(:left node) (:right node)])

(defmethod ground? :prt [node]
  (and (ground? (:left node))
       (ground? (:right node))))

(defmethod min-length :prt [node]
  (+ (min-length (:left node))
     (min-length (:right node))))

(defmethod max-length :prt [node]
  (+ (max-length (:left node))
     (max-length (:right node))))

(defmethod unparse :prt [node]
  `(~@(unparse (:left node))
    ~@(when-some [right (seq (unparse (:right node)))]
        (if (tail-node? (:right node))
          right
          `(~'. ~@right)))))

;; This is not really a good definition. While it is true that finding
;; solutions for a series variable length subsequence patterns
;; would require searching, it does not imply there is more than one
;; solution. For example, the pattern
;;
;;   [1 2 ... 3 4 ...]
;;
;; can only have one solution. Therefore, for patterns such as these
;; this method should return false.
(defmethod search? :prt
  [{left :left, right :right}]
  (or (and (variable-length? left)
           (variable-length? right))
      (search? left)
      (search? right)))

;; :quo

(defmethod ground? :quo [_]
  true)

(defmethod unparse :quo [node]
  `(quote ~(:form node)))

(defmethod search? :quo [_]
  false)

;; :ref

(defmethod children :ref [node]
  [])

(defmethod ground? :ref [_]
  false)

(defmethod unparse :ref [node]
  (:symbol node))

(defmethod search? :ref [_]
  false)

;; :rp*

(defmethod children :rp* [node]
  [(:cat node)])

(defmethod ground? :rp* [_]
  false)

(defmethod min-length :rp* [_]
  0)

(defmethod max-length :rp* [_]
  ##Inf)

(defmethod unparse :rp* [node]
  `(~@(unparse (:cat node)) ~'...))

(defmethod search? :rp* [_]
  false)

;; :rp+

(defmethod children :rp+ [node]
  [(:cat node)])

(defmethod ground? :rp+ [_]
  false)

(defmethod min-length :rp+ [node]
  (let [n (:n node)]
    (if (integer? n)
      (* n (min-length (:cat node)))
      0)))

(defmethod max-length :rp+ [_]
  ##Inf)

(defmethod unparse :rp+ [node]
  (let [dots (if-some [n (:n node)]
               (symbol (str ".." n))
               '..)]
    `(~@(unparse (:cat node)) ~dots)))

(defmethod search? :rp+ [_]
  false)

;; :rpl

(defmethod children :rpl [node]
  [(:cat node) (:lvr node)])

(defmethod ground? :rpl [_]
  false)

(defmethod min-length :rpl [node]
  0)

(defmethod max-length :rpl [_]
  ##Inf)

(defmethod unparse :rpl [node]
  (let [dots (symbol (str ".." (unparse (:lvr node))))]
    `(~@(unparse (:cat node)) ~dots)))

(defmethod search? :rpl [_]
  false)

;; :rpm

(defmethod children :rpm [node]
  [(:cat node) (:mvr node)])

(defmethod ground? :rpm [_]
  false)

(defmethod min-length :rpm [node]
  0)

(defmethod max-length :rpm [_]
  ##Inf)

(defmethod unparse :rpm [node]
  (let [dots (symbol (str ".." (unparse (:mvr node))))]
    `(~@(unparse (:cat node)) ~dots)))

(defmethod search? :rpm [_]
  false)

;; :rst

(defmethod children :rst [node]
  [(:mvr node)])

(defmethod ground? :rst [_]
  false)

(defmethod min-length :rst [_]
  0)

(defmethod max-length :rst [_]
  ##Inf)

(defmethod unparse :rst [node]
  (list (unparse (:mvr node)) '...))

(defmethod search? :rst [_]
  false)

;; :set

(defmethod children :set [node]
  (if-some [rest-node (:rest node)]
    (concat (:elements node)
            (if-some [rest-set (:rest node)]
              (let [xs (children rest-set)]
                (if (seq xs)
                  xs
                  [rest-set])))
            (if-some [as (:as node)]
              (let [xs (children as)]
                (if (seq xs)
                  xs
                  [as]))))
    (:elements node)))

(defmethod ground? :set [node]
  (every? ground? (:elements node)))

(defmethod unparse :set [node]
  (cond-> (set (map unparse (:elements node)))
    (some? (:as node))
    (conj (vary-meta (unparse (:as node)) assoc :as true))

    (some? (:rest node))
    (conj (vary-meta (unparse (:as node)) assoc :tag '&))))

(defmethod search? :set [node]
  (not (ground? node)))

;; seq

(defmethod children :seq [node]
  [(:prt node)])

(defmethod ground? :seq [node]
  (and (ground? (:prt node))
       (nil? (:as node))))

(defmethod unparse :seq [node]
  (seq (unparse (:prt node))))

(defmethod search? :seq [node]
  (search? (:prt node)))

(defmethod min-length :seq [node]
  (min-length (:prt node)))

(defmethod max-length :seq [node]
  (max-length (:prt node)))

;; :tail

(defmethod children :tail [node]
  [(:pattern node)])

(defmethod ground? :tail [node]
  (ground? (:pattern node)))

(defmethod unparse :tail [node]
  (list '& (unparse (:pattern node))))

(defmethod search? :tail [node]
  (search? (:pattern node)))

;; To compute the `min-length` and `max-length` for `:tail` depends on
;; the pattern it originated from. When the pattern looks similar to
;;
;;     (_ ... & (1 2 3))
;;     ---------^^^^^^
;;
;; where the underlined pattern is the `:pattern` of the `:tail`, then
;; `min-length` and `max-length` can be derived from this pattern. When
;; the pattern looks something like
;;
;;     (_ ... & ?x)
;;     ---------^^
;;
;; where the underlined pattern is the `:pattern` of the `:tail, then
;; we fail back using `0` and `##Inf` for `min-length` and
;; `max-length` respectively.

(defmethod min-length :tail [node]
  (let [pattern (:pattern node)]
    (if (min-length? pattern)
      (min-length pattern)
      0)))

(defmethod max-length :tail [node]
  (let [pattern (:pattern node)]
    (if (max-length? pattern)
      (max-length pattern)
      ##Inf)))

;; :unq

(defmethod ground? :unq [_]
  true)

(defmethod unparse :unq [node]
  (list 'clojure.core/unquote (:expr node)))

(defmethod search? :unq [_]
  false)

;; :uns

(defmethod ground? :uns [_]
  false)

(defmethod unparse :uns [node]
  (list 'clojure.core/unquote-splicing (:expr node)))

(defmethod search? :uns [_]
  false)

;; :vec

(defmethod children :vec [node]
  [(:prt node)])

(defmethod ground? :vec [node]
  (and (ground? (:prt node))
       (nil? (:as node))))

(defmethod min-length :vec [node]
  (min-length (:prt node)))

(defmethod max-length :vec [node]
  (max-length (:prt node)))

(defmethod unparse :vec [node]
  (cond-> (vec (unparse (:prt node)))
    (some? (:as node))
    (conj :as (unparse (:as node)))))

(defmethod search? :vec [node]
  (search? (:prt node)))

;; wth

(defmethod children :wth [node]
  [(:body node)])

(defmethod ground? :wth [node]
  (ground? (:body node)))

(defmethod unparse :wth [node]
  `(~'with [~@(mapcat
               (juxt
                (comp unparse :ref)
                (comp unparse :pattern))
               (:bindings node))]
    ~@(when-some [body (:body node)]
        [(unparse body)])))

(defmethod search? :wth [node]
  ;; Come back to to this.
  true)

;; ---------------------------------------------------------------------
;; walk

(defmulti walk
  "Same as clojure.walk/walk but for AST nodes."
  {:arglists '([inner outer node])}
  (fn [_ _ node]
    (tag node))
  :default ::default)

(defmethod walk ::default
  [inner outer x]
  (if (node? x)
    (outer x)
    x))

(defn postwalk
  [f node]
  (walk (fn [x]
          (let [y (f x)]
            (if (reduced? y)
              (deref y)
              (postwalk f y))))
        f
        node))

(defn prewalk
  [f node]
  (let [x (f node)]
    (if (reduced? x)
      (deref x)
      (walk (partial prewalk f) identity x))))

(defn prewalk-replace
  "Same as clojure.walk/prewalk-replace but for AST nodes."
  [smap form]
  (prewalk (fn [x] (if (contains? smap x) (smap x) x)) form))

(defn postwalk-replace
  "Same as clojure.walk/postwal-replace but for AST nodes."
  [smap form] (postwalk (fn [x] (if (contains? smap x) (smap x) x)) form))

(defmethod walk :cat [inner outer node]
  (outer (assoc node :elements (mapv inner (:elements node)))))

(defmethod walk :ctn [inner outer node]
  (outer (assoc node :pattern (inner (:pattern node)))))

(defmethod walk :jsa [inner outer node]
  (outer (assoc node :prt (inner (:prt node)))))

(defmethod walk :jso [inner outer node]
  (outer (assoc node :object (reduce
                              (fn [m [k-node v-node]]
                                (assoc m (inner k-node) (inner v-node)))
                              {}
                              (:object node)))))

(defmethod walk :map [inner outer node]
  (outer (assoc node
                :rest-map (if-some [rest-map (:rest-map node)]
                            (inner rest-map))
                :map (reduce
                      (fn [m [k-node v-node]]
                        (assoc m (inner k-node) (inner v-node)))
                      {}
                      (:map node)))))

(defmethod walk :prt [inner outer node]
  (outer (assoc node
                :left (inner (:left node))
                :right (inner (:right node)))))

(defmethod walk :rp* [inner outer node]
  (outer (assoc node :cat (inner (:cat node)))))

(defmethod walk :rp+ [inner outer node]
  (outer (assoc node :cat (inner (:cat node)))))

(defmethod walk :rpl [inner outer node]
  (outer (assoc node :cat (inner (:cat node)))))

(defmethod walk :rpm [inner outer node]
  (outer (assoc node :cat (inner (:cat node)))))

(defmethod walk :set [inner outer node]
  (outer (assoc node
                :rest (if-some [rest-set (:rest node)]
                        (inner rest-set))
                :elements (mapv inner (:elements node)))))

(defmethod walk :seq [inner outer node]
  (outer (assoc node :prt (inner (:prt node)))))

(defmethod walk :tail [inner outer node]
  (outer (assoc node :pattern (inner (:pattern node)))))

(defmethod walk :vec [inner outer node]
  (outer (assoc node :prt (inner (:prt node)))))

(defmethod walk :wth [inner outer node]
  (outer (assoc node
                :bindings (mapv
                           (fn [binding]
                             (assoc binding :pattern (inner (:pattern binding))))
                           (:bindings node))
                :body (inner (:body node)))))

;; ---------------------------------------------------------------------
;; Other useful utilities

(defn make-variable-rename-map
  [node]
  (reduce
   (fn [index [i node]]
     (if (variable-node? node)
       (if (contains? index node)
         index
         (assoc index node (assoc node :symbol (symbol (str "?v__" i)))))
       index))
   {}
   (map vector (range) (subnodes node))))

(defn genref
  {:private true}
  []
  {:tag :ref
   :symbol (gensym "%r__")})

(defn ref-smap
  {:private true}
  [with-node]
  (into {} (map
            (fn [binding]
              [(:ref binding) (genref)]))
        (:bindings with-node)))

(defn rename-refs
  "Give all distinct :ref nodes a unique :symbol."
  [node]
  (postwalk
   (fn [node]
     (if (with-node? node)
       (let [ref-smap (ref-smap node)]
         (assoc node
                :bindings (map
                           (fn [binding]
                             {:ref (get ref-smap (:ref binding))
                              :pattern (postwalk-replace ref-smap (:pattern binding))})
                           (:bindings node))
                :body (postwalk-replace ref-smap (:body node))))
       node))
   node))

(defn consolidate-with
  "Collapse all :wth nodes into a single :wth node."
  [node]
  (let [state (volatile! [])
        node (prewalk
              (fn f [node]
                (if (with-node? node)
                  (do (vswap! state into (:bindings node))
                      (:body node))
                  node))
              node)]
    {:tag :wth
     :bindings (deref state)
     :body node}))

(s/def :meander.syntax.epsilon/ref-map
  (s/map-of :meander.syntax.epsilon.node/ref
            :meander.syntax.epsilon/node))

(s/fdef make-ref-map
  :args (s/cat :node :meander.syntax.epsilon/node)
  :ret :meander.syntax.epsilon/ref-map)

(defn make-ref-map
  "If node is a node repesenting a with pattern, return a map from
  reference to pattern node derived from it's bindings, otherwise
  return an empty map."
  [node]
  (if (with-node? node)
    (into {} (map (juxt :ref :pattern)) (:bindings node))
    {}))

(s/fdef substitute-refs
  :args (s/alt :a1 (s/cat :node :meander.syntax.epsilon/node)
               :a2 (s/cat :node :meander.syntax.epsilon/node
                          :ref-map :meander.syntax.epsilon/ref-map))
  :ret :meander.syntax.epsilon/node)

(defn substitute-refs
  "Given node and an optional ref-map "
  ([node]
   (substitute-refs node {}))
  ([node ref-map]
   (prewalk
    (fn f [node]
      (cond
        (ref-node? node)
        (reduced
         (if-some [other-node (get ref-map node)]
           (substitute-refs other-node (dissoc ref-map node))
           node))

        (with-node? node)
        (reduced
         (if-some [body (:body node)]
           (let [ref-map (reduce
                          (fn [ref-map [k v]]
                            (if (contains? ref-map k)
                              ref-map
                              (assoc ref-map k v)))
                          (make-ref-map node)
                          ref-map)]
             (substitute-refs body ref-map))
           node))

        :else
        node))
    node)))

(defn literal?
  "true if node is ground and does not contain :map or :set subnodes,
  false otherwise.

  The constraint that node may not contain :map or :set subnodes is
  due to the semantics of map and set patterns: they express submap
  and subsets respectively. Compiling these patterns to literals as
  part of an equality check would result in false negative matches.

  See also: compile-ground"
  [node]
  (and (ground? node)
       (not-any? (comp #{:map :unq :set} tag)
                 (subnodes node))))

(defn partition-nodes
  "Given a `partition-node?` returns a vector of its `:left` and
  `:right` nodes recursively and in order i.e. `:left` or `:right` is
  a `partition-node?` then the returned vector will include the result
  of applying `partition-nodes` to them.

  Example:

      (let [vec-node (parse '[_ ..2 1 . 1 2 3 ... !xs])
            prt-node (:prt vec-node)]
        (map unparse (partition-nodes prt-node)))
      ;; =>
      ((_ ..2) (1) (1 2 3 ...) (!xs) ())"
  {:arglists '([partition-node])
   :private true}
  [node]
  (s/assert :meander.syntax.node.epsilon/partition node)
  (let [left (:left node)
        right (:right node)]
    (concat (if (partition-node? left)
              (partition-nodes left)
              (list left))
            (if (partition-node? right)
              (partition-nodes right)
              (list right)))))

(defn partition-from-nodes
  {:private true}
  [nodes]
  (reduce
   (fn [prt-node node]
     {:tag :prt
      :left node
      :right prt-node})
   (last nodes)
   (reverse (butlast nodes))))

(defn window [node]
  (s/assert :meander.syntax.node.epsilon/partition node)
  (let [p-nodes (partition-nodes node)]
    (if (<= 3 (count p-nodes))
      (let [[a b c] p-nodes]
        (if (and (= (:tag a) :drp)
                 (= (:tag b) :cat)
                 (= (:tag c) :drp))
          (let [rest-p-nodes (drop 2 p-nodes)]
            (if (and (= (count rest-p-nodes) 2)
                     (= (:tag (nth rest-p-nodes 0)) :drp)
                     (and (= (:tag (nth rest-p-nodes 1)) :cat)
                          (= (max-length (nth rest-p-nodes 1)) 0)))
              [b nil]
              [b (partition-from-nodes rest-p-nodes)])))))))

(defn lit-form
  [node]
  (case (tag node)
    :cat
    (map lit-form (:elements node))

    :jsa
    #?(:clj
       (JSValue. (vec (lit-form (:prt node))))
       :cljs
       (into-array (lit-form (:prt node))))

    :lit
    (:value node)

    :map
    (into {}
          (map (fn [[k v]]
                 [(lit-form k) (lit-form v)]))
          (:map node))

    :prt
    (concat (lit-form (:left node))
            (lit-form (:right node)))

    :quo
    (:form node)

    :vec
    (into [] (lit-form (:prt node)))

    :seq
    (if-some [l (seq (lit-form (:prt node)))]
      l
      ())

    :set
    (into #{} (map lit-form (:elements node)))))

;; ---------------------------------------------------------------------
;; defsyntax

(defmacro defsyntax
  {:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
               [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  [& defn-args]
  (let [conformed-defn-args (s/conform ::core.specs/defn-args defn-args)
        defn-args (next defn-args)
        docstring (:docstring conformed-defn-args)
        defn-args (if docstring
                    (next defn-args)
                    defn-args)
        meta (:meta conformed-defn-args)
        defn-args (if meta
                    (next defn-args)
                    defn-args)
        meta (if docstring
               (merge {:doc docstring} meta)
               meta)
        variadic? (= (first (:fn-tail conformed-defn-args))
                     :arity-n)
        arglists (if variadic?
                   (map first defn-args)
                   (list (first defn-args)))
        meta (assoc meta :arglists (list 'quote arglists))
        fn-name (:fn-name conformed-defn-args)
        meta (merge meta (clojure.core/meta fn-name))
        fn-name (with-meta fn-name meta)
        body (if variadic?
               defn-args
               (list defn-args))
        body (map
              (fn [fn-spec]
                `(~(first fn-spec)
                  (let [~'&form *form*
                        ~'&env *env*]
                    ~@(rest fn-spec))))
              body)
        qfn-name (symbol (name (ns-name *ns*))
                         (name fn-name))
        expander-definition-body-form
        `(do (def ~fn-name (fn ~@body))
             (let [expander# (fn expander# [form# env#]
                               (binding [*form* form#
                                         *env* env#]
                                 (apply ~fn-name (rest form#))))]
               (swap! global-expander-registry assoc '~qfn-name expander#)))]
    ;; When defining new syntax in ClojureScript it is also necessary
    ;; to define the methods which parse and expand the syntax in
    ;; Clojure. This is because the match, search, and find macros (in
    ;; meander.match.epsilon) are expanded in Clojure which, in turn,
    ;; rely on these methods.
    #?(:clj
       (when-some [cljs-ns (:ns &env)]
         ;; Visit the namespace.
         (in-ns (:name cljs-ns))
         ;; Try to require the namespace or everything in
         ;; :requires. Both operations can fail.
         (try
           (require (:name cljs-ns))
           (catch Exception _
             (doseq [[alias ns-name] (:requires cljs-ns)]
               (if (= alias ns-name)
                 (require ns-name)
                 (require [ns-name :as alias])))))
         (eval expander-definition-body-form)))
    `(do ~expander-definition-body-form
         (var ~fn-name))))

;; ClojureScript seems to have this weird quirk where we need to ask
;; for the spec twice. The first time blows up with an error saying it
;; can't find it, the second time works like a charm. Needless to say,
;; we can't have this namespace cause breakage by virtue of requiring
;; it and getting this error. Needs investigation.
#?(:clj
   (s/fdef defsyntax
     :args ::core.specs/defn-args))