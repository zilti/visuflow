# VisuFlow

In functional programming, we decouple the data from the functionality.  
But why don't we decouple the data flow from the functions?  
...
A Clojure library designed to help you organize the order of your functions.

## Contents
1. [Usage](#Usage)
1.1. [Ingredients](#ingredients---a-small-overview)
1.1. [The nested structure](#the-nested-structure)
1.1. [Calling functions and keywords - The result stack](#calling-functions-and-keywords---the-result-stack)
1.1.1. [The :!p keyword](#the-p-keyword)
1.1. [Fork - If, the VisuFlow way](#fork---if-the-visuflow-way)
1.1.1. [Something to consider...](#something-to-consider)
1.1.1. [Validargs](#validargs)
1.1.1.1. [Composing validargs](#composing-validargs)
1.1.1. [More control - the flow :! colonbangs](#more-control---the-flow--colonbangs)
1. [TODO List](#todo-list)
1. [License](#License)

## Usage

```clojure
[visuflow "0.2.0"]
```

### Ingredients - a small overview...
For this to work, you need functions you want to link together.

Then you need some kind of "state" you want to pass through.

Most likely you'll need a map of *validargs*.
Validargs are keywords referring to a "testing scheme". More on that later.

Finally, you need a nested structure describing what you want to do.

To start, do:
```clojure
(use 'visuflow.core)
(walk {:state 5 :tree (... walking tree ...) :validargs {... validarg map ...})
```

### The nested structure
The nested structure describes how your data flows through your functions:
Lists hold everything together:
```clojure
(list first [:!f identity :true] ([println true]) ([println false]))
```
If a list entry consists of more than one thing, you wrap them in a vector:
```clojure
[:!fp #(inc %) :data]
```
Finally, a sub-structure is again a list:
```clojure
(:mapkey)
```

### Calling functions and keywords - The result stack
VisuFlow tries to intelligently use the args your embedded function takes.
First, an important thing: Throughout a walk, VisuFlow keeps all results in a stack-like structure.
That is implemented as a Clojure list using conj, take and drop.

At first, the stack only contains the "state" you passed in. Then, after each function call
the result gets put on top of it.

*What has this to do with the arity of your functions?*
That's simple: Passing in a one-arg function, VisuFlow hands it the topmost value of the stack.
Passing in a two-arg function, it gets the two topmost args, and so on. Until you run out of stack.

#### The :!p keyword
Look at this example code:
```clojure
;; We assume the stack is (7 6 5 4 3 2 1)
(defn increment [x] (inc x))
(defn addition [x y] (+ x y))

[:!p increment] ;;=> (8 6 5 4 3 2 1)
[:!p addition] ;;=> (13 5 4 3 2 1)

increment ;;=> (8 7 6 5 4 3 2 1)
addition ;;=> (13 7 6 5 4 3 2 1)
```
The p in :!p stands for "pop", which means "take the first value from the stack and remove it".
At the end of each function call, the result gets put back.

### Fork - If, the VisuFlow way
When you need to decide which function to execute next, you need to fork your way.
This is done using fork statements:
```clojure
[:!f #(inc %) :data]
```
The fork statement consists of three elements.

1. The keyword to distinguish it: :!f means "fork", and :!fp means fork, but pop the values taken for the function from the stack.

2. The validator: A function which gets applied just as the others in the data flow.

3. A "validarg": A keyword telling VisuFlow how to interpret the result of the validator.

#### Something to consider...
The reader "steps" into nested lists, never to leave them again:
```clojure
(walk {:state 1 :tree (list inc inc dec [:!f identity :<5] inc dec) :validargs {:<5 [[#(< % 5) 1]
                                                                                     [#(>= % 5) 2]]}}) 
;;=> 2 ;; Stack: (2 3 2 2 3 2 1)

(walk {:state 1 :tree (list inc inc dec [:!f identity :<5] (inc) (dec)) :validargs {:<5 [[#(< % 5) 1]
                                                                                         [#(>= % 5) 2]]}})
;;=> 3 ;; Stack: (3 2 2 3 2 1)
```

#### Validargs
There are some built-in validargs:  
:data "if input is a value other than true, false, nil"  
:true "if input is true"  
:false "if input is false"  
:nil "if input is nil"  
:fnil "if input is false or nil"  
:is "A vector query like above with custom checkers."	

Furthermore you can define your own in the validargs-map you have to supply at the beginning:
```clojure
{:type [[map? 1]
        [list? 2]
	    [vector? 3]
	    [:else 4]]}
```
It looks like a sub-flow. Each vector specifies an if-then statement.
First, on the left side you can have anything that's a function and takes one argument,
or :else.

On the right side you have more options:

1. Numbers: Tells the walker how much to drop from the list it's walking on. The walker can only ever work on the first element of the list. 0 means: Run the fork again.  

2. A keyword: Tells the walker: Drop one and continue at the list at the map value of the keyword.
If no map is found, the walker will look if the first element of the list is equal to the keyword.
Don't forget: This is the only place where you can use maps! A map without fork will crash.  

3. Chaining: You can chain numbers and keywords together in a sequence (like this: [map? [3 :foo]]).
e.g. [3 :foo] means: Drop three and continue as in 2., with the exception that no more entries will be dropped. :foo is an implicit [1 :foo]. 

##### Composing validargs
You can also compose validargs. That is very simple and works the same way you would compose functions with clojure.core/comp:
```clojure
(comp validargs
[[[map? 1]
  [list? 2]]
 [[#(> 5 %) {}]
  [:else (list)]]])
```
This will return a new vector-list where, if the input number is smaller than 5 it will drop 1, else it will drop 2.

### More control - the flow :! colonbangs
```clojure
:!_#n ;; Clean the stack and only keep the last n values.
:!d#n ;; *D*rop n: This overrules the normal (drop 1) after every command
:!q ;; *Q*uit the flow and return the last result (NOT RECOMMENDED!)
```
You can either write them into the control flow, or let a function return it as the first value of a tuple(but that won't work in a fork!). Furthermore you can compose them as you can with validargs.
Note: When using the flow-:!-statements, nothing will get dropped from the tree, you have to do that yourself! e.g. [:!_#2 :!d#1] To keep last 2 stack entries and drop one entry from the tree.

## TODO List
* Do something useful when the element is neiter a keyword, function, nor a list.
* Add a :!j cb to join in another flow tree.
* -Something I forgot belongs here-

## License

Copyright © 2013 Daniel Ziltener

Distributed under the Eclipse Public License, the same as Clojure.
