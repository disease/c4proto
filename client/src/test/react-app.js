
import "babel-polyfill"
import React from 'react'
import SSEConnection from "../main/sse-connection"
import Feedback      from "../main/feedback"
import activate      from "../main/activator"
import withState     from "../main/active-state"
import {VDomCore,VDomAttributes} from "../main/vdom-core"
import {VDomSender,pairOfInputAttributes} from "../main/vdom-util"
import {mergeAll}    from "../main/util"
import * as Canvas   from "../main/canvas"
import CanvasManager from "../main/canvas-manager"
import {ExampleAuth} from "../test/vdom-auth"
import {ExampleRequestState} from "../test/request-state"
import CanvasExtraMix from "../extra/canvas-extra-mix"
import {CanvasBaseMix,CanvasSimpleMix} from "../main/canvas-mix"


function fail(data){ alert(data) }

const send = fetch

const feedback = Feedback(localStorage,sessionStorage,document.location,send)
window.onhashchange = () => feedback.pong()
const sender = VDomSender(feedback)
const exampleRequestState = ExampleRequestState(sender)

const log = v => console.log(v)
const getRootElement = () => document.body

const util = Canvas.CanvasUtil()

const exchangeMix = options => canvas => Canvas.ExchangeCanvasSetup(canvas)
const canvasMods = [CanvasBaseMix(log,util),exchangeMix,CanvasExtraMix(log)]

const canvas = CanvasManager(Canvas.CanvasFactory(util, canvasMods), sender, log)

const exampleAuth = ExampleAuth(pairOfInputAttributes)
const vDomAttributes = VDomAttributes(exampleRequestState)
const transforms = mergeAll([vDomAttributes.transforms, exampleAuth.transforms, canvas.transforms])

const vDom = VDomCore(log,transforms,getRootElement)

const receiversList = [vDom.receivers,feedback.receivers,{fail},exampleRequestState.receivers]
const createEventSource = () => new EventSource(location.protocol+"//"+location.host+"/sse")

const connection = SSEConnection(createEventSource, receiversList, 5000)
activate(requestAnimationFrame, withState(log,[connection.checkActivate,vDom.checkActivate,canvas.checkActivate]))
