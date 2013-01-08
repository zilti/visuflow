(ns visiflow.core)

(defmacro cond-let
  "Takes a binding-form and a set of test/expr pairs. Evaluates each test
  one at a time. If a test returns logical true, cond-let evaluates and
  returns expr with binding-form bound to the value of test and doesn't
  evaluate any of the other tests or exprs. To provide a default value
  either provide a literal that evaluates to logical true and is
  binding-compatible with binding-form, or use :else as the test and don't
  refer to any parts of binding-form in the expr. (cond-let binding-form)
  returns nil."
  [bindings & clauses]
  (let [binding (first bindings)]
    (when-let [[test expr & more] clauses]
      (if (= test :else)
        expr
        `(if-let [~binding ~test]
           ~expr
           (cond-let ~bindings ~@more))))))

(defn arg-count [f]
  (let [m (first (.getDeclaredMethods (class f)))
        p (.getParameterTypes m)]
    (alength p)))

;; Input: any clojure data structure or thing
;; It is built of clojure lists or vectors.

;; Fundamental rule: A thing accepting one argument gets the last result,
;; a thing accepting two arguments gets the two last ones, and so on, on a FIFO base.
;; You can add the metadata-key :maxargs to define the max number of args for a vararg fun,
;; or write it like [function maxargs].

;; First entry in a vector:
;; If it's a keyword, flow will apply it to the input.
;; Then: When the input is a function it gets applyed, with the whole data structure as argument.
;; After that, or if it's a data structure, it gets handed to the next thing in the list.

;; If that's a function or keyword, it gets applyed.

;; Takes input map valargs:
;; Number says how many to drop. 1 means, drop validator expression, take next.
{:st {:is [[map? 1]
           [list? 2]
           [:else 2]]}}

;; Validarg types
{:data "if input is a value other than true, false, nil"
 :true "if input is true"
 :false "if input is false"
 :nil "if input is nil"
 :fnil "if input is false or nil"
 :is "A vector query like above with custom checkers."}

;; Possible return values
{1 "or any other number. How many entries to drop. 0 means evaluate the same expression again."
 :foo "Continue at that key in the map after dropping 1."
 :?foo "Continue at the vector where this is the first entry."
 :?_ "Recommendation for default keyword in a vector."
 [3 :foo] "Chaining. Numbers mean: drop. Keywords mean: :foo Key of a map :?foo \"key of a coll\""}

(defn- v-data
  [arg]
  (if-not (or (nil? arg) (false? arg) (true? arg))
    1 2))

(defn- v-true
  [arg]
  (if (true? arg)
    1 2))

(defn- v-false
  [arg]
  (if (false? arg)
    1 2))

(defn- v-nil
  [arg]
  (if (nil? arg)
    1 2))

(defn- v-fnil
  [arg]
  (if (or (false? arg) (nil? arg))
    1 2))

(defn v-is
  [arg validarg-list]
  (reduce (fn [rval [fun ret]]
            (if (= :else fun)
              ret
              (if (true? (apply fun [arg]))
                ret
                rval)))
          nil
          validarg-list))

(defn- popstack
  [{:keys [stack count]}]
  [(take count stack) (drop count stack)])

(defn- getstack
  [{:keys [stack count]}]
  [(take count stack) stack])

(defn- apply-validarg
  [{:keys [inp varg validargs]}]
  (case varg
    :data (v-data inp)
    :true (v-true inp)
    :false (v-false inp)
    :nil (v-nil inp)
    :fnil (v-fnil inp)
    (if-let [is-varg (varg validargs)]
      (v-is inp is-varg)
      (throw (Exception. (str "Validarg does not exist: " varg " in " validargs))))))

(defn- eval-with-stack
  [{:keys [pop? car args stack]}]
  (let [arity (if (nil? args)
                (arg-count car)
                (- (arg-count car) (count args)))
        [stackargs stack] (if pop?
                            (popstack {:stack stack :count arity})
                            (getstack {:stack stack :count arity}))
        result (if (nil? args)
                 (apply car stackargs)
                 (apply car (flatten (conj [] args stackargs))))]
    (conj stack result)))

(defn- parse-validarg-stmt "Parses one validarg statement and applies it to the coll."
  [coll stmt]
  (cond
   (integer? stmt)
   (drop stmt coll)
   
   (keyword? stmt)
   (->> coll (drop 1) stmt)
   
   :else
   coll))

(defn- parse-validarg-res "Coordinates parsing of a list of validarg statements."
  [coll [car & cdr]]
  (if (or (empty? cdr) (nil? cdr))
    (if (nil? car)
      coll
      (parse-validarg-stmt coll car))
    (parse-validarg-res (parse-validarg-stmt coll car) cdr)))

(defn- eval-fork "Evaluates a fork expression"
  [{:keys [coll vargs stack]}]
  (let [fork (first coll)
        funstruct (eval (second fork))
        fun (if (fn? funstruct) funstruct (first funstruct))
        funargs (if (fn? funstruct) nil (rest funstruct))
        validarg (last fork)
        stack (eval-with-stack {:pop? (= :!fp (first fork)) :car fun :args funargs :stack stack})
        result (first stack)
        vargres (apply-validarg {:inp result :varg validarg :validargs vargs})]
    [(parse-validarg-res vargres) (conj stack result)]))

(defn- eval-elem
  [{:keys [coll vargs stack]}]
  (let [car (first coll)
        arg (first stack)]
    (cond
     (and (keyword? car) (.startsWith (name car) ":!"))
     [coll stack]
     
     (or (keyword? car) (fn? car))
     [(drop 1 coll) (eval-with-stack :car car :stack stack)]
     
     (sequential? car)
     (if (or (= :!f (first car)) (= :!fp (first car)))
       (eval-fork :coll coll :vargs vargs :stack stack)
       
       (let [pop? (= :!p (first car))
             cdr (if pop? (rest car) car)
             fun (first cdr)
             args (rest cdr)]
        [(drop 1 coll) (eval-with-stack :pop? pop? :car fun :args args :stack stack)]))
     
     :else ;; TODO broken
     [(drop 1 coll) (conj stack (arg car))])))

(defn walk
  ([{:keys [state tree validargs]}]
     (walk true :tree tree :validargs validargs :stack (conj (list) state)))
  
  ([x {:keys [tree validargs stack]}]
     (cond
      (list? (first tree))
      (walk true :tree (first tree) :validargs validargs :stack stack)
      
      (empty? (rest tree))
      (eval-elem :coll tree :vargs validargs :stack stack)
      
      :else
      (let [[tree stack] (eval-elem :coll tree :vargs validargs :stack stack)]
        (walk true :tree tree :validargs validargs :stack stack)))))