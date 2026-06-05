var shadow$provide = {};
(function(){
'use strict';/*

 Copyright The Closure Library Authors.
 SPDX-License-Identifier: Apache-2.0
*/
function v(a){return null!=a&&!1!==a};function De(a){var b=document.documentElement;"dark"===a||"light"===a?b.setAttribute("data-theme",a):b.removeAttribute("data-theme")}function Ee(){var a=document.documentElement.getAttribute("data-theme");return"dark"===a?!0:"light"===a?!1:v(window.matchMedia)?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}function d(a){return a.setAttribute("aria-pressed",v(Ee())?"true":"false")}
function Fe(){var a=document.querySelector("[data-theme-toggle]");return v(a)?(a.removeAttribute("hidden"),d(a),a.addEventListener("click",function(){var b=v(Ee())?"light":"dark";De(b);try{window.localStorage.setItem("smia-theme",b)}catch(c){}return d(a)})):null};var e;try{e=window.localStorage.getItem("smia-theme")}catch(a){e=null}De(e);"loading"===document.readyState?document.addEventListener("DOMContentLoaded",function(){return Fe()}):Fe();
}).call(this);