var shadow$provide = {};
(function(){
'use strict';/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
function c(a){return null!=a&&!1!==a};function e(a){a=document.querySelector(a);return c(a)?a.click():null}function f(a){if(c(a)){var b=a.tagName,d="INPUT"===b;return d||(d="TEXTAREA"===b)?d:(b="SELECT"===b)?b:a.isContentEditable}return null}function g(){return document.querySelector("[data-kbd-help]")}function h(){var a=g();c(a)?(a=a.hasAttribute("hidden"),a=null==a?!0:!1===a?!0:!1):a=!1;return a}function k(){var a=g();return c(a)?a.setAttribute("hidden","hidden"):null}
function l(){var a=document.querySelector("[data-kbd-close]");c(a)&&a.addEventListener("click",function(){return k()});var b=g();return c(b)?b.addEventListener("click",function(d){return d.target===b?k():null}):null};document.addEventListener("keydown",function(a){var b=a.key;return"Escape"===b?h()?k():null:c(f(a.target))?null:"?"===b?(a.preventDefault(),h()?a=k():(a=g(),a=c(a)?a.removeAttribute("hidden"):null),a):"/"===b?(a.preventDefault(),a=document.querySelector("form.search input"),c(a)?a.focus():null):"ArrowLeft"===b||"h"===b?e('a[rel\x3d"prev"]'):"ArrowRight"===b||"l"===b?e('a[rel\x3d"next"]'):"d"===b?e("[data-theme-toggle]"):"f"===b?e("[data-focus-toggle]"):"g"===b?e(".book-sidebar-title, .page-nav a"):
null});"loading"===document.readyState?document.addEventListener("DOMContentLoaded",function(){return l()}):l();
}).call(this);