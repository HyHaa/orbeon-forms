/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.event.events

import org.orbeon.oxf.xforms.event.XFormsEvent._
import org.orbeon.oxf.xforms.event.XFormsEvents._
import org.orbeon.oxf.xforms.event.{XFormsEventTarget, XFormsEvent}
import org.orbeon.saxon.om._

class XXFormsValueChanged(target: XFormsEventTarget, properties: PropertyGetter)
    extends XFormsEvent(XXFORMS_VALUE_CHANGED, target, properties, bubbles = true, cancelable = true) {

    def this(target: XFormsEventTarget, node: NodeInfo, oldValue: String, newValue: String) = {
        this(target, EmptyGetter)
        nodeOpt = Option(node)
        oldValueOpt = Option(oldValue)
        newValueOpt = Option(newValue)
    }

    private var nodeOpt: Option[NodeInfo] = None
    def node = nodeOpt.orNull

    private var oldValueOpt: Option[String] = None
    private var newValueOpt: Option[String] = None
    def newValue = newValueOpt.get

    override def lazyProperties = getters(this, XXFormsValueChanged.Getters)
}

private object XXFormsValueChanged {

    val Getters = Map[String, XXFormsValueChanged ⇒ Option[Any]](
        "node"      → (_.nodeOpt),
        "old-value" → (_.oldValueOpt),
        "new-value" → (_.newValueOpt)
    )
}