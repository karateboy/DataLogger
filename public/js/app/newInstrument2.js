/**
 * newInstrument2
 */

'use strict';
angular.module('newInstrumentView', [])

.factory('InstTypeInfoService', [ '$resource', function($resource) {
	return $resource('/InstrumentTypeInfo/:id');
} ])

.controller('newInstrumentCtrl',
		[ 'InstTypeInfoService', function($instTypeInfoService) {
			var self = this;
			self.instrumentTypeInfos = $instTypeInfoService.query();
			
			self.selectedInstType = {id:'t100'};
			//self.instType["t100"] = true; 
									
			self.isSelected= function(id){
				return {
					active: id==self.selectedInstType.id
				};
			};			
		} ]);
