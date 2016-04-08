/**
 * 
 */
angular.module('mtConfigView', ['ngTable']).factory('MonitorTypeService',
		[ '$resource', function($resource) {
			return $resource('/MonitorType/:id', {
				id : '@_id'
			});
		} ])

.controller('mtConfigCtrl',
		[ 'MonitorTypeService', 'NgTableParams', function($monitorType, NgTableParams) {
			var self = this;
			self.monitorTypeList = $monitorType.query();
			self.displayInstrument = function(mt) {
				if (mt.measuringBy)
					return mt.measuringBy;
				else if (mt.measuredBy)
					return mt.measuredBy + "(停用)";
				else
					return "未使用";
			}

			
			self.tableParams = new NgTableParams({
				page : 1, // show first page
				count : 10
			// count per page
			}, {
				filterDelay : 0,
				data : self.displayInstrument
			});
			
		} ]);
