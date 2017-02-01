
import * as Canvas from "../main/canvas"
import CanvasMix from "../main/canvas-mix"
import * as CanvasExtra from "canvas-extra"

export default function CanvasExtraMix(getCanvasData){
    CanvasMix(getCanvasData, canvas => [
        Canvas.TiledCanvasSetup(canvas), //Canvas.SingleTileCanvasSetup
        Canvas.DragViewPositionCanvasSetup(canvas), //CanvasExtra.ScrollViewPositionCanvasSetup
        CanvasExtra.OverlayCanvasSetup(canvas), //Canvas.NoOverlayCanvasSetup
        CanvasExtra.BoundTextCanvasSetup(canvas),
        CanvasExtra.DragAndDropCanvasSetup(canvas),
        CanvasExtra.TransitionCanvasSetup(canvas)
    ])
}
